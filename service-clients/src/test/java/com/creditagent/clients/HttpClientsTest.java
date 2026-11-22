package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpClientsTest {

    @Test
    void theConnectTimeoutIsItsOwnAndRedirectsAreNotFollowed() {
        HttpClient client = HttpClients.httpClient(Duration.ofSeconds(2));
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void aSlowAnswerGetsTheReadTimeoutNotTheConnectTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            RestClient client = HttpClients.restClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(10), Duration.ofMillis(200), RetrySettings.none(), false);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.get().uri("/").retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
        } finally {
            server.stop(0);
        }
    }
}
