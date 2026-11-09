package com.creditagent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class A2aCreditAdvisorPortTest {

    @Test
    void anUnreachableAgentFailsEachCallAndIsTriedAgainNextTime() throws Exception {
        // The agent is up but cannot serve its card: building the A2A client (which fetches the card)
        // fails, and because the failed client is not kept, the next call fetches the card again.
        AtomicInteger cardRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                cardRequests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
        try {
            A2aCreditAdvisorPort port =
                    new A2aCreditAdvisorPort("http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> port.requestAdvice("123456789")).isInstanceOf(RuntimeException.class);
            int afterFirst = cardRequests.get();
            assertThat(afterFirst).isPositive();
            assertThatThrownBy(() -> port.requestAdvice("123456789")).isInstanceOf(RuntimeException.class);
            assertThat(cardRequests.get()).as("the card is fetched again on the next call").isGreaterThan(afterFirst);
        } finally {
            server.stop(0);
        }
    }
}
