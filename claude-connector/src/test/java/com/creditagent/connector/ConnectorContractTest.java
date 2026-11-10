package com.creditagent.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ScoreService;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * Starts the real connector and talks to it the way Claude does: unauthenticated first (to be sent
 * to the OAuth server), then with a bearer token, through the LangChain4j MCP client. The web server
 * and the other services are mocked at their client interfaces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "credit.connector.public-url=https://connector.example/",
    "credit.connector.authorization-server=https://credit.example",
    "credit.connector.allowed-origins=https://allowed.example",
    "credit.connector.requests-per-user-per-minute=20"
})
class ConnectorContractTest {

    static final String RESOURCE = "https://connector.example/mcp";
    static final String TOKEN = "eyJhbGciOiJkaXIifQ..iv.ciphertext.tag";
    static final Ssn SSN = Ssn.parse("123-45-6789");
    static final CreditReport REPORT = new CreditReport("r-1", "***-**-6789", "Avery Doe", Instant.EPOCH, List.of(), 0, 0, 0);

    @LocalServerPort
    int port;

    @MockitoBean
    AccountClient accounts;

    @MockitoBean
    AdviceClient advice;

    @MockitoBean
    ScoreService scores;

    @MockitoBean
    CreditReportSource reports;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        given(accounts.authenticate(TOKEN)).willReturn(Optional.of(user("avery", RESOURCE, "credit")));
        given(reports.findReport(SSN)).willReturn(Optional.of(REPORT));
        given(scores.score(REPORT)).willReturn(CreditScore.of(712));
    }

    static ConnectorUser user(String id, String resource, String scope) {
        return new ConnectorUser(id, SSN, TOKEN, resource, scope);
    }

    record Answer(int status, HttpHeaders headers, String body) {}

    Answer post(String body, Map<String, String> headers) {
        return http.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::add))
                .body(body)
                .exchange((request, response) -> new Answer(response.getStatusCode().value(), response.getHeaders(),
                        new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    @Test
    void withoutATokenClaudeIsPointedAtTheResourceMetadata() {
        Answer answer = post(PING, Map.of());
        assertThat(answer.status()).isEqualTo(401);
        assertThat(answer.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer resource_metadata=\"https://connector.example/.well-known/oauth-protected-resource\", scope=\"credit\"");

        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = http.get().uri(path).retrieve().body(Map.class);
            assertThat(metadata).containsEntry("resource", RESOURCE)
                    .containsEntry("authorization_servers", List.of("https://credit.example"))
                    .containsEntry("scopes_supported", List.of("credit"));
        }
    }

    @Test
    void theRealMcpClientListsAndCallsTheToolsWithAToken() throws Exception {
        try (McpClient client = DefaultMcpClient.builder()
                .key("contract-test")
                .transport(StreamableHttpMcpTransport.builder()
                        .url("http://localhost:" + port + "/mcp")
                        .customHeaders(Map.of("Authorization", "Bearer " + TOKEN))
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .protocolVersion("2025-11-25")
                .build()) {
            assertThat(client.listTools()).extracting(t -> t.name()).containsExactlyInAnyOrder(
                    "get_credit_score", "get_credit_report", "get_credit_advice", "get_report_lock_status", "set_report_lock");
            String score = client.executeTool(ToolExecutionRequest.builder()
                    .id("1").name("get_credit_score").arguments("{}").build()).resultText();
            assertThat(score).contains("\"score\":712").contains("\"band\":\"GREEN\"").doesNotContain("123456789");
        }
    }

    @Test
    void aBadlyShapedTokenIsRefusedWithoutAskingTheWebServer() {
        Answer answer = post(PING, Map.of("Authorization", "Bearer not-a-jwe"));
        assertThat(answer.status()).isEqualTo(401);
        assertThat(answer.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
        assertThat(post(PING, Map.of("Authorization", "Bearer " + "a.".repeat(3000) + "b.c.d.e")).status()).isEqualTo(401);
        assertThat(post(PING, Map.of("Authorization", "Basic abc")).status()).isEqualTo(401);
        assertThat(post(PING, Map.of("Authorization", "Bearer   ")).status()).isEqualTo(401);
        verify(accounts, never()).authenticate(anyString());
    }

    @Test
    void anInactiveTokenOrOneForAnotherResourceIsInvalid() {
        given(accounts.authenticate("a.b.c.d.e")).willReturn(Optional.empty());
        assertThat(post(PING, Map.of("Authorization", "Bearer a.b.c.d.e")).headers().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("invalid_token");

        given(accounts.authenticate("f.g.h.i.j")).willReturn(Optional.of(user("avery", "https://other.example/mcp", "credit")));
        assertThat(post(PING, Map.of("Authorization", "Bearer f.g.h.i.j")).status()).isEqualTo(401);
    }

    @Test
    void aTokenWithoutTheCreditScopeIs403() {
        given(accounts.authenticate("k.l.m.n.o")).willReturn(Optional.of(user("avery", RESOURCE, "other")));
        Answer answer = post(PING, Map.of("Authorization", "Bearer k.l.m.n.o"));
        assertThat(answer.status()).isEqualTo(403);
        assertThat(answer.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("insufficient_scope");
    }

    @Test
    void anIntrospectionOutageIs503WithRetryAfter() {
        given(accounts.authenticate("p.q.r.s.t")).willThrow(new ServiceUnavailableException("down", null));
        Answer answer = post(PING, Map.of("Authorization", "Bearer p.q.r.s.t"));
        assertThat(answer.status()).isEqualTo(503);
        assertThat(answer.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void eachUserIsRateLimited() {
        given(accounts.authenticate("u.v.w.x.y")).willReturn(Optional.of(user("busy", RESOURCE, "credit")));
        for (int i = 0; i < 20; i++) {
            assertThat(post(PING, Map.of("Authorization", "Bearer u.v.w.x.y")).status()).isEqualTo(200);
        }
        Answer limited = post(PING, Map.of("Authorization", "Bearer u.v.w.x.y"));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.headers().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        // Another user is unaffected.
        assertThat(post(PING, Map.of("Authorization", "Bearer " + TOKEN)).status()).isEqualTo(200);
    }

    @Test
    void browserOriginsMustBeAllowed() {
        assertThat(post(PING, Map.of("Authorization", "Bearer " + TOKEN, "Origin", "https://evil.example")).status())
                .isEqualTo(403);
        assertThat(post(PING, Map.of("Authorization", "Bearer " + TOKEN, "Origin", "https://allowed.example")).status())
                .isEqualTo(200);
    }

    @Test
    void notificationsAre202AndOtherMethodsAre405() {
        assertThat(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                Map.of("Authorization", "Bearer " + TOKEN)).status()).isEqualTo(202);
        Integer get = http.get().uri("/mcp").exchange((rq, rs) -> rs.getStatusCode().value());
        Integer delete = http.delete().uri("/mcp").exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(get).isEqualTo(405);
        assertThat(delete).isEqualTo(405);
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() {
        String huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
                + "x".repeat((int) McpHttpController.MAX_BODY_BYTES) + "\"}}";
        assertThat(post(huge, Map.of("Authorization", "Bearer " + TOKEN)).status()).isEqualTo(413);
        verify(accounts, never()).authenticate(any());
    }
}
