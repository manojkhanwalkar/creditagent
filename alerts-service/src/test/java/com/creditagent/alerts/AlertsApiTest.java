package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/** The real server: service token, validation, size limit and the answer's shape. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "credit.alerts.service-token=" + AlertsApiTest.TOKEN,
    "credit.alerts.dynamodb-endpoint=http://localhost:1"
})
class AlertsApiTest {

    static final String TOKEN = "service-token-service-token-service-token";

    @LocalServerPort
    int port;

    @MockitoBean
    AlertRepository alerts;

    record Answer(int status, String body) {}

    Answer lookup(String body, boolean withToken) {
        return RestClient.create().post().uri("http://localhost:" + port + AlertsController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (withToken) {
                        h.setBearerAuth(TOKEN);
                    }
                })
                .body(body)
                .exchange((rq, rs) -> new Answer(rs.getStatusCode().value(), new String(rs.getBody().readAllBytes())));
    }

    @Test
    void anExposedEmailComesBackWithWhereAndWhenButNotTheEmail() {
        given(alerts.find("ada@example.com")).willReturn(List.of(
                new Alert("ada@example.com", "breach-forum-beta", Instant.parse("2026-11-09T12:00:00Z"))));
        Answer answer = lookup("{\"email\":\"ada@example.com\"}", true);
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("\"exposed\":true").contains("breach-forum-beta").contains("2026-11-09T12:00:00Z")
                .doesNotContain("ada@example.com");
    }

    @Test
    void anUnknownEmailIsNotExposed() {
        given(alerts.find("bob@example.com")).willReturn(List.of());
        assertThat(lookup("{\"email\":\"bob@example.com\"}", true).body()).contains("\"exposed\":false").contains("\"findings\":[]");
    }

    @Test
    void onlyHoldersOfTheServiceTokenMayAsk() {
        assertThat(lookup("{\"email\":\"ada@example.com\"}", false).status()).isEqualTo(401);
        Integer health = RestClient.create().get().uri("http://localhost:" + port + "/actuator/health")
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(health).isEqualTo(200);
    }

    @Test
    void everyAddressRegistrationAcceptsCanBeLookedUp() {
        // Not RFC-clean, but registration accepts it: the look-up must too, or that user never sees alerts.
        given(alerts.find("a..b@example.com")).willReturn(List.of());
        assertThat(lookup("{\"email\":\"a..b@example.com\"}", true).status()).isEqualTo(200);
    }

    @Test
    void aDynamoDbFailureIs503WithRetryAfter() {
        given(alerts.find("ada@example.com")).willThrow(software.amazon.awssdk.core.exception.SdkClientException.create("down"));
        Answer answer = RestClient.create().post().uri("http://localhost:" + port + AlertsController.PATH)
                .contentType(MediaType.APPLICATION_JSON).headers(h -> h.setBearerAuth(TOKEN))
                .body("{\"email\":\"ada@example.com\"}")
                .exchange((rq, rs) -> new Answer(rs.getStatusCode().value(), rs.getHeaders().getFirst("Retry-After")));
        assertThat(answer).isEqualTo(new Answer(503, "1"));
    }

    @Test
    void badInputIs400AndHugeBodiesAre413() {
        assertThat(lookup("{\"email\":\"not-an-email\"}", true).status()).isEqualTo(400);
        assertThat(lookup("{}", true).status()).isEqualTo(400);
        String huge = "{\"email\":\"" + "a".repeat((int) AlertsConfiguration.MAX_BODY_BYTES) + "@example.com\"}";
        assertThat(lookup(huge, true).status()).isEqualTo(413);
        assertThat(Map.of()).isEmpty();
    }
}
