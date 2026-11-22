package com.creditagent.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.alerts.Alert;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RemoteEmailSenderTest {

    static final String TOKEN = "internal-token-for-tests-only-".repeat(2);
    static final Instant RUN = Instant.parse("2026-11-12T08:00:00Z");
    static final List<Alert> ALERTS = List.of(
            new Alert("ada@example.com", "paste-site", Instant.parse("2026-11-11T10:00:00Z")),
            new Alert("ada@example.com", "forum", Instant.parse("2026-11-09T23:59:00Z")));

    record Received(String method, String path, String authorization, String key, String contentType, String body) {}

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile int status = 202;
    volatile String accepted = "SENT";
    volatile long delayMillis;

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
            byte[] answer = (status == 202 ? "{\"id\":\"n1\",\"status\":\"" + accepted + "\"}" : "{\"status\":" + status + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 503 || status == 429) {
                exchange.getResponseHeaders().add("Retry-After", "1");
            }
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

    RemoteEmailSender sender(Duration timeout) {
        return RemoteEmailSender.create("http://127.0.0.1:" + server.getAddress().getPort(), timeout, TOKEN);
    }

    RemoteEmailSender sender() {
        return sender(Duration.ofSeconds(5));
    }

    @Test
    void theAlertIsPostedAsTheTemplateWithTheTokenAndAKeyWithoutTheAddress() throws Exception {
        sender().sendAlert("ada@example.com", ALERTS, RUN, "https://credit.example");

        Received r = received.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/internal/v1/notifications");
        assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(r.key()).matches("alert:[A-Za-z0-9_-]{43}:" + RUN.toEpochMilli()).doesNotContain("ada", "example");
        Map<?, ?> json = JsonMapper.builder().build().readValue(r.body(), Map.class);
        assertThat(json).isEqualTo(Map.of("channel", "EMAIL", "to", "ada@example.com", "template", "DARK_WEB_ALERT",
                "params", Map.of("count", "2", "firstSeen", "2026-11-09", "appUrl", "https://credit.example")));
    }

    @Test
    void theKeyIsStableForOneUserAndRunAndDiffersOtherwise() {
        RemoteEmailSender sender = sender();
        String key = sender.idempotencyKey("ada@example.com", RUN);
        assertThat(sender.idempotencyKey("ada@example.com", RUN)).isEqualTo(key);
        assertThat(sender.idempotencyKey("bob@example.com", RUN)).isNotEqualTo(key);
        assertThat(sender.idempotencyKey("ada@example.com", RUN.plusSeconds(86_400))).isNotEqualTo(key);
        assertThat(RemoteEmailSender.create("http://x", Duration.ofSeconds(1), TOKEN + "x")
                .idempotencyKey("ada@example.com", RUN)).isNotEqualTo(key);
        assertThat(key.length()).isLessThanOrEqualTo(128);
    }

    @Test
    void appUrlIsLeftOutWhenBlank() {
        assertThat(RemoteEmailSender.body("ada@example.com", ALERTS.subList(0, 1), " "))
                .containsEntry("params", Map.of("count", "1", "firstSeen", "2026-11-11"));
        assertThat(RemoteEmailSender.body("ada@example.com", ALERTS.subList(0, 1), null).get("params"))
                .isEqualTo(Map.of("count", "1", "firstSeen", "2026-11-11"));
    }

    @Test
    void any202IsSuccessIncludingAnUncertainEarlierSend() {
        accepted = "UNCERTAIN";
        sender().sendAlert("ada@example.com", ALERTS, RUN, "");
        assertThat(received).hasSize(1);
    }

    @Test
    void a422IsRefused() {
        status = 422;
        assertThatThrownBy(() -> sender().sendAlert("ada@example.com", ALERTS, RUN, ""))
                .isInstanceOf(RemoteEmailSender.Refused.class).hasMessageNotContaining("ada");
    }

    @Test
    void unknownOutcomesAreSentOnce() {
        for (int unknown : new int[] {503, 504, 429, 500}) {
            received.clear();
            status = unknown;
            assertThatThrownBy(() -> sender().sendAlert("ada@example.com", ALERTS, RUN, ""))
                    .as("status %d", unknown).isInstanceOf(RemoteEmailSender.Unknown.class);
            assertThat(received).hasSize(1);
        }
    }

    @Test
    void callerBugsAreIllegalState() {
        for (int bug : new int[] {400, 401, 404, 409}) {
            status = bug;
            assertThatThrownBy(() -> sender().sendAlert("ada@example.com", ALERTS, RUN, ""))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(Integer.toString(bug));
        }
    }

    @Test
    void aTimeoutOrAnUnreachableServiceIsUnknown() throws IOException {
        delayMillis = 1_000;
        assertThatThrownBy(() -> sender(Duration.ofMillis(200)).sendAlert("ada@example.com", ALERTS, RUN, ""))
                .isInstanceOf(RemoteEmailSender.Unknown.class);
        assertThat(received).hasSize(1);

        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertThatThrownBy(() -> RemoteEmailSender.create("http://127.0.0.1:" + closed, Duration.ofSeconds(2), TOKEN)
                .sendAlert("ada@example.com", ALERTS, RUN, "")).isInstanceOf(RemoteEmailSender.Unknown.class);
    }

    @Test
    void freeTextIsNeverSentAndATokenIsRequired() {
        assertThatThrownBy(() -> sender().send("ada@example.com", "s", "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(received).isEmpty();
        assertThat(sender().configured()).isTrue();
        for (String token : new String[] {null, " "}) {
            assertThatThrownBy(() -> RemoteEmailSender.create("http://x", Duration.ofSeconds(1), token))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_API_TOKEN");
        }
        assertThatThrownBy(() -> RemoteEmailSender.body("a@example.com", List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusedAndUnknownSendsReleaseTheClaimsForTheNextRun() {
        AlertNotifierTest.InMemoryAlerts alerts = new AlertNotifierTest.InMemoryAlerts();
        ALERTS.forEach(alerts::record);
        for (int failure : new int[] {422, 503}) {
            status = failure;
            AlertNotifier notifier = new AlertNotifier(alerts, sender(), AlertNotifierTest.properties(100),
                    Clock.fixed(RUN, ZoneOffset.UTC), d -> { }, (email, now) -> true);
            AlertNotifier.Run run = notifier.run();
            assertThat(run.emailsFailed()).isEqualTo(1);
            assertThat(alerts.claims).isEmpty();
        }
        status = 202;
        assertThat(new AlertNotifier(alerts, sender(), AlertNotifierTest.properties(100), Clock.fixed(RUN, ZoneOffset.UTC),
                d -> { }, (email, now) -> true).run().emailsSent()).isEqualTo(1);
        assertThat(alerts.claims).hasSize(2);
    }
}
