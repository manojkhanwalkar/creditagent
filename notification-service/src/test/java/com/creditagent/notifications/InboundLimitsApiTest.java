package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** On a real server, with the filters as the application registers them. */
class InboundLimitsApiTest {

    static HttpResponse<String> post(int port, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + NotificationController.PATH))
                .header("Content-Type", "application/json")
                .header(NotificationController.IDEMPOTENCY_KEY, "k")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "credit.notifications.service-token=" + ApiTestSupport.TOKEN,
        "credit.notifications.dynamodb-endpoint=http://localhost:1",
        "credit.notifications.email=log",
        "credit.notifications.sms=log",
        "credit.inbound.burst=1",
        "credit.inbound.rate-per-second=0.001"
    })
    class OverTheRateLimit {

        @LocalServerPort
        int port;

        @Test
        void aRequestOverTheTokenBucketIs429WithRetryAfter() throws Exception {
            assertThat(post(port, ApiTestSupport.TOKEN).statusCode()).isEqualTo(400); // the one token: reaches validation
            HttpResponse<String> limited = post(port, ApiTestSupport.TOKEN);
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "credit.notifications.service-token=",
        "credit.notifications.dynamodb-endpoint=http://localhost:1",
        "credit.notifications.email=log",
        "credit.notifications.sms=log"
    })
    class WithoutAConfiguredToken {

        @LocalServerPort
        int port;

        @Test
        void theApiIsOffForEveryoneButHealthStillAnswers() throws Exception {
            assertThat(post(port, null).statusCode()).isEqualTo(404);
            assertThat(post(port, ApiTestSupport.TOKEN).statusCode()).isEqualTo(404);
            HttpResponse<String> health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        }
    }
}
