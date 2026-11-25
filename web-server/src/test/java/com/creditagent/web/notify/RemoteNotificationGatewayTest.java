package com.creditagent.web.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.clients.RetrySettings;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpDeliveryException;
import com.creditagent.web.notify.NotificationGateway.Notification;
import com.creditagent.web.notify.NotificationGateway.Template;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RemoteNotificationGatewayTest {

    static final String TOKEN = "t".repeat(40);
    static final RetrySettings RETRY_ONCE = new RetrySettings(2, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO);
    static final Notification CODE = new Notification(OtpChannel.SMS, "+15555550100", Template.OTP_SIGN_IN,
            Map.of("code", "042917", "validSeconds", "300"), "otp:challenge-1:1");

    record Received(String method, String path, String authorization, String key, String contentType, String body) {}

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile int status = 202;
    volatile long delayMillis;
    volatile String retryAfter;
    volatile String accepted = "SENT";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), new String(body, StandardCharsets.UTF_8)));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            byte[] answer = status == 202 ? "{\"id\":\"n1\",\"status\":\"%s\"}".formatted(accepted).getBytes(StandardCharsets.UTF_8)
                    : "{\"status\":%d}".formatted(status).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 202 ? "application/json" : "application/problem+json");
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    RemoteNotificationGateway gateway(Duration timeout, RetrySettings retry) {
        return RemoteNotificationGateway.create(RestClient.builder(), url(), Duration.ofSeconds(2), timeout, retry, TOKEN);
    }

    RemoteNotificationGateway gateway() {
        return gateway(Duration.ofSeconds(5), RETRY_ONCE);
    }

    @Test
    void aCodeIsPostedWithTheTokenTheKeyAndTheContractBody() throws Exception {
        gateway().send(CODE);

        assertThat(received).hasSize(1);
        Received r = received.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/internal/v1/notifications");
        assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(r.key()).isEqualTo("otp:challenge-1:1");
        assertThat(r.contentType()).startsWith("application/json");
        Map<?, ?> json = JsonMapper.builder().build().readValue(r.body(), Map.class);
        assertThat(json).isEqualTo(Map.of("channel", "SMS", "to", "+15555550100", "template", "OTP_SIGN_IN",
                "params", Map.of("code", "042917", "validSeconds", "300")));
    }

    @Test
    void a202ForAnUncertainEarlierSendCountsAsSuccess() {
        accepted = "UNCERTAIN";
        gateway().send(CODE);
        assertThat(received).hasSize(1);
    }

    @Test
    void a422IsRefusedSoTheSendIsGivenBack() {
        status = 422;
        assertThatThrownBy(() -> gateway().send(CODE)).isInstanceOfSatisfying(OtpDeliveryException.class, e -> {
            assertThat(e.refused()).isTrue();
            assertThat(e.channel()).isEqualTo(OtpChannel.SMS);
        });
        assertThat(received).hasSize(1);
    }

    @Test
    void unknownOutcomesKeepTheCodeValidAndAreNeverRetried() {
        // Without Retry-After too: the shared interceptor would retry a 503 or 429 at once if it were on.
        for (String hint : new String[] {null, "1"}) {
            for (int unknown : new int[] {503, 504, 429, 500, 502}) {
                received.clear();
                status = unknown;
                retryAfter = hint;
                assertThatThrownBy(() -> gateway().send(CODE)).as("status %d, Retry-After %s", unknown, hint)
                        .isInstanceOfSatisfying(OtpDeliveryException.class, e -> assertThat(e.refused()).isFalse());
                assertThat(received).as("tries for %d, Retry-After %s", unknown, hint).hasSize(1);
            }
        }
    }

    @Test
    void callerBugsAreServerErrorsNotDeliveryFailures() {
        for (int bug : new int[] {400, 401, 404, 409, 413}) {
            status = bug;
            assertThatThrownBy(() -> gateway().send(CODE)).as("status %d", bug)
                    .isInstanceOf(IllegalStateException.class).isNotInstanceOf(OtpDeliveryException.class)
                    .hasMessageContaining(Integer.toString(bug));
        }
    }

    @Test
    void aTimeoutIsAnUnknownOutcomeAndIsNotRetried() {
        delayMillis = 1_000;
        assertThatThrownBy(() -> gateway(Duration.ofMillis(200), RETRY_ONCE).send(CODE))
                .isInstanceOfSatisfying(OtpDeliveryException.class, e -> assertThat(e.refused()).isFalse());
        assertThat(received).hasSize(1);
    }

    @Test
    void aFailedConnectIsRetriedWithTheSameKey() {
        AtomicInteger attempts = new AtomicInteger();
        RestClient client = RestClient.builder().baseUrl(url())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))
                .requestInterceptor((request, body, execution) -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new ConnectException("Connection refused");
                    }
                    return execution.execute(request, body);
                })
                .build();
        new RemoteNotificationGateway(client, RETRY_ONCE, TOKEN).send(CODE);

        assertThat(attempts).hasValue(2);
        assertThat(received).singleElement().extracting(Received::key).isEqualTo("otp:challenge-1:1");
    }

    @Test
    void aServiceThatCannotBeReachedIsAnUnknownOutcomeAfterTheRetries() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        RemoteNotificationGateway down = RemoteNotificationGateway.create(RestClient.builder(), "http://127.0.0.1:" + closed,
                Duration.ofSeconds(2), Duration.ofSeconds(2), RETRY_ONCE, TOKEN);
        assertThatThrownBy(() -> down.send(CODE))
                .isInstanceOfSatisfying(OtpDeliveryException.class, e -> assertThat(e.refused()).isFalse());
    }

    @Test
    void onlyConnectFailuresCountAsRetryable() {
        assertThat(RemoteNotificationGateway.connectFailed(new ConnectException("refused"))).isTrue();
        assertThat(RemoteNotificationGateway.connectFailed(new java.net.http.HttpTimeoutException("read"))).isFalse();
        assertThat(RemoteNotificationGateway.connectFailed(new IllegalStateException("x"))).isFalse();
    }

    @Test
    void onlySignInCodesAreSentAndATokenIsRequired() {
        assertThatThrownBy(() -> gateway().send(new Notification(OtpChannel.EMAIL, "a@example.com", Template.DARK_WEB_ALERT,
                Map.of(), "k"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(received).isEmpty();
        for (String token : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> RemoteNotificationGateway.create(RestClient.builder(), url(), Duration.ofSeconds(1),
                    Duration.ofSeconds(1), RETRY_ONCE, token)).isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_API_TOKEN");
        }
    }

    @Test
    void theDeliveryErrorNeverHoldsTheCodeOrDestination() {
        status = 422;
        assertThatThrownBy(() -> gateway().send(CODE)).satisfies(e -> {
            String text = e + " " + e.getCause();
            assertThat(text).doesNotContain("042917", "+15555550100", TOKEN);
        });
    }

    @Test
    void aNotificationPrintsNeitherTheDestinationNorTheCodeNorTheKey() {
        Notification n = new Notification(OtpChannel.SMS, "+15555550100", Template.OTP_SIGN_IN,
                Map.of("code", "042917", "validSeconds", "300"), "otp:challenge-id:1");
        assertThat(n.toString()).contains("SMS", "OTP_SIGN_IN", "[code, validSeconds]")
                .doesNotContain("042917", "+15555550100", "challenge-id");
    }
}
