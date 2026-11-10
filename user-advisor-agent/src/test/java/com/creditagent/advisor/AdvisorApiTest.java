package com.creditagent.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.advisor.memory.ConversationMemory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The real server: service-token filter, size limit, validation and the JSON contract the web server uses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "credit.advisor.service-token=" + AdvisorApiTest.TOKEN)
class AdvisorApiTest {

    static final String TOKEN = "service-token-service-token-service-token";
    static final String ID = "conversation-0123456789";

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ConversationMemory memory;

    RestClient client(String token) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
    }

    String body(String message) {
        return json.writeValueAsString(Map.of("message", message, "report", TestReports.stretched()));
    }

    @Test
    void chatsRemembersAndForgets() {
        RestClient web = client(TOKEN);
        Map<?, ?> first = web.post().uri("/internal/v1/conversations/{id}/messages", ID)
                .contentType(MediaType.APPLICATION_JSON).body(body("What is my utilization?"))
                .retrieve().body(Map.class);
        assertThat(first.get("refused")).isEqualTo(false);
        assertThat((String) first.get("reply")).startsWith("Your card balances");
        assertThat((String) first.get("disclaimer")).contains("educational purposes only");

        Map<?, ?> refused = web.post().uri("/internal/v1/conversations/{id}/messages", ID)
                .contentType(MediaType.APPLICATION_JSON).body(body("what's the weather like today"))
                .retrieve().body(Map.class);
        // A short follow-up is allowed once a conversation exists, so test a long off-topic one.
        Map<?, ?> offTopic = web.post().uri("/internal/v1/conversations/{id}/messages", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("Please write a long poem about the ocean waves and the moon over the quiet sleepy harbour town"))
                .retrieve().body(Map.class);
        assertThat(offTopic.get("refused")).isEqualTo(true);
        assertThat(refused.get("reply")).isNotNull();

        assertThat(memory.history(ID)).isNotEmpty();
        Integer status = web.delete().uri("/internal/v1/conversations/{id}", ID)
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(204);
        assertThat(memory.history(ID)).isEmpty();
    }

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThatThrownBy(() -> client("wrong-token-wrong-token-wrong-token").delete()
                .uri("/internal/v1/conversations/{id}", ID).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        Integer health = RestClient.create().get().uri("http://localhost:" + port + "/actuator/health")
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(health).isEqualTo(200);
    }

    @Test
    void aBadConversationIdOrBodyIs400() {
        assertThatThrownBy(() -> client(TOKEN).delete().uri("/internal/v1/conversations/{id}", "short")
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> client(TOKEN).post().uri("/internal/v1/conversations/{id}/messages", ID)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"hi\"}").retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() {
        String huge = body("x".repeat((int) AdvisorConfiguration.MAX_BODY_BYTES));
        Integer status = client(TOKEN).post().uri("/internal/v1/conversations/{id}/messages", ID)
                .contentType(MediaType.APPLICATION_JSON).body(huge)
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(413);
    }
}
