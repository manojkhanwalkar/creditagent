package com.creditagent.web.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.clients.RetrySettings;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditReport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The real clients ChatConfiguration builds, against a real socket that answers 502 then 200: a chat
 * message, which adds to the conversation, must not be sent twice; a forget may be.
 */
class ChatRetryTest {

    static final String TOKEN = "service-token-service-token-service-token";

    HttpServer server;
    final AtomicInteger chatHits = new AtomicInteger();
    final AtomicInteger forgetHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/conversations/", exchange -> {
            boolean chat = exchange.getRequestURI().getPath().endsWith("/messages");
            int hit = (chat ? chatHits : forgetHits).incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (hit == 1) {
                exchange.sendResponseHeaders(502, -1);
            } else if (chat) {
                byte[] body = "{\"reply\":\"r\",\"disclaimer\":\"d\",\"refused\":false}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    AdvisorClient client() {
        ChatProperties properties = new ChatProperties("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
        RetrySettings retry = new RetrySettings(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(5), Duration.ZERO);
        return ChatConfiguration.buildAdvisorClient(RestClient.builder(), properties, retry, TOKEN);
    }

    @Test
    void aChatMessageThatMayHaveArrivedIsNotSentAgain() {
        CreditReport report = new CreditReport(null, null, null, Instant.EPOCH, List.of(), 0, 0, 0);
        assertThatThrownBy(() -> client().chat("conversation-0123456789", "q", report))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(chatHits.get()).isEqualTo(1);
    }

    @Test
    void aForgetIsRetried() {
        client().forget("conversation-0123456789");
        assertThat(forgetHits.get()).isEqualTo(2);
    }
}
