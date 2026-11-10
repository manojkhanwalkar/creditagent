package com.creditagent.offers;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real server: the REST API and the MCP interface give the same offers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = "credit.offers.service-token=" + OffersApiTest.TOKEN)
class OffersApiTest {

    static final String TOKEN = "service-token-service-token-service-token";
    static final Map<String, String> AUTH = Map.of("Authorization", "Bearer " + TOKEN);

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    record Answer(int status, String body) {}

    Answer post(String path, String body, Map<String, String> headers) {
        java.util.Map<String, String> all = new java.util.HashMap<>(AUTH);
        all.putAll(headers);
        return RestClient.create().post().uri("http://localhost:" + port + path)
                .contentType(MediaType.APPLICATION_JSON).headers(h -> all.forEach(h::set)).body(body)
                .exchange((rq, rs) -> new Answer(rs.getStatusCode().value(), new String(rs.getBody().readAllBytes())));
    }

    @Test
    void restAndMcpGiveTheSameOffers() throws Exception {
        Answer rest = post(OffersRestController.OFFERS_PATH, "{\"score\":720,\"zip\":\"98101\"}", Map.of());
        assertThat(rest.status()).isEqualTo(200);
        JsonNode viaRest = json.readTree(rest.body());
        assertThat(viaRest.path("offers").size()).isBetween(1, 10);
        assertThat(viaRest.path("state").asString()).isEqualTo("WA");

        try (McpClient client = DefaultMcpClient.builder().key("offers-test")
                .transport(StreamableHttpMcpTransport.builder().url("http://localhost:" + port + "/mcp")
                        .customHeaders(AUTH).timeout(Duration.ofSeconds(10)).build())
                .protocolVersion("2025-11-25").build()) {
            assertThat(client.listTools()).anySatisfy(t -> assertThat(t.name()).isEqualTo(OfferTools.TOOL));
            String text = client.executeTool(ToolExecutionRequest.builder().id("1").name(OfferTools.TOOL)
                    .arguments("{\"score\":720,\"zip\":\"98101\"}").build()).resultText();
            JsonNode viaMcp = json.readTree(text);
            assertThat(viaMcp.path("offers").findValuesAsString("offerId"))
                    .isEqualTo(viaRest.path("offers").findValuesAsString("offerId"));
        }
    }

    @Test
    void restWithoutAZipGivesNationwideOffers() {
        Answer rest = post(OffersRestController.OFFERS_PATH, "{\"score\":650}", Map.of());
        assertThat(rest.status()).isEqualTo(200);
        assertThat(rest.body()).contains("\"state\":null").doesNotContain("Credit Union");
    }

    @Test
    void badRestInputIs400() {
        assertThat(post(OffersRestController.OFFERS_PATH, "{\"score\":900}", Map.of()).status()).isEqualTo(400);
        assertThat(post(OffersRestController.OFFERS_PATH, "{\"zip\":\"98101\"}", Map.of()).status()).isEqualTo(400);
        assertThat(post(OffersRestController.OFFERS_PATH, "{\"score\":700,\"zip\":\"981\"}", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void theMcpEndpointBehaves() {
        assertThat(post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of()).body()).contains("\"result\":{}");
        assertThat(post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of()).status()).isEqualTo(202);
        assertThat(post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of("Origin", "https://evil.example")).status())
                .isEqualTo(403);
        assertThat(post("/mcp", "not json", Map.of()).body()).contains("-32700");
        assertThat(post("/mcp", "[1]", Map.of()).body()).contains("-32600");
        // String ids are fine (JSON-RPC allows them) and come back as sent.
        assertThat(post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":\"req-7\",\"method\":\"tools/list\"}", Map.of()).body())
                .contains("\"id\":\"req-7\"").contains("get_credit_card_offers");
        assertThat(post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"ping\"}", Map.of()).body()).contains("-32600");
        String badArgs = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + OfferTools.TOOL + "\",\"arguments\":{\"score\":10}}}", Map.of()).body();
        assertThat(badArgs).contains("\"isError\":true");
        Integer get = RestClient.create().get().uri("http://localhost:" + port + "/mcp").header("Authorization", "Bearer " + TOKEN)
                .exchange((rq, rs) -> rs.getStatusCode().value());
        Integer delete = RestClient.create().delete().uri("http://localhost:" + port + "/mcp").header("Authorization", "Bearer " + TOKEN)
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(get).isEqualTo(405);
        assertThat(delete).isEqualTo(405);
    }

    @Test
    void bothInterfacesNeedTheServiceToken() {
        for (String path : new String[] {OffersRestController.OFFERS_PATH, OffersRestController.MCP_PATH}) {
            Integer status = RestClient.create().post().uri("http://localhost:" + port + path)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"score\":700}")
                    .exchange((rq, rs) -> rs.getStatusCode().value());
            assertThat(status).as(path).isEqualTo(401);
        }
        Integer health = RestClient.create().get().uri("http://localhost:" + port + "/actuator/health")
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(health).isEqualTo(200);
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() {
        String huge = "{\"score\":700,\"zip\":\"" + "9".repeat((int) OffersRestController.MAX_BODY_BYTES) + "\"}";
        assertThat(post(OffersRestController.OFFERS_PATH, huge, Map.of()).status()).isEqualTo(413);
    }
}
