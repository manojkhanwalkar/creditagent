package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exercises the LangChain4j MCP client wiring against a minimal in-process MCP server. */
class McpCreditReportSourceTest {

    static final String REPORT_JSON = """
            {"reportId":"r-1","ssnMasked":"***-**-6789","fullName":"Avery Patel",
             "generatedAt":"2026-11-01T12:00:00Z","accounts":[],
             "hardInquiriesLast12Months":2,"collections":0,"publicRecords":0}
            """;

    final JsonMapper json = JsonMapper.builder().build();
    /** Three tries, with millisecond waits so the tests stay fast. */
    static final RetrySettings FAST_RETRY =
            new RetrySettings(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5), Duration.ofMillis(1));

    final AtomicBoolean failing = new AtomicBoolean();
    /** The next this many requests are refused with {@link #refuseWith}, then the server recovers. */
    final java.util.concurrent.atomic.AtomicInteger failNext = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    volatile int refuseWith = 503;
    final AtomicBoolean hang = new AtomicBoolean();
    HttpServer server;
    McpCreditReportSource source;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        source = new McpCreditReportSource(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "test", Duration.ofSeconds(5),
                FAST_RETRY, Duration.ofMillis(1), json);
    }

    @AfterEach
    void stop() {
        source.close();
        server.stop(0);
    }

    @Test
    void readsAndParsesTheReport() {
        Optional<CreditReport> report = source.findReport(Ssn.parse("123-45-6789"));
        assertThat(report).hasValueSatisfying(r -> {
            assertThat(r.reportId()).isEqualTo("r-1");
            assertThat(r.hardInquiriesLast12Months()).isEqualTo(2);
        });
    }

    @Test
    void resourceNotFoundBecomesEmpty() {
        assertThat(source.findReport(Ssn.parse("234-56-7890"))).isEmpty();
    }

    @Test
    void transportFailuresAreServiceUnavailableWithoutLeakingTheSsn() {
        failing.set(true);
        assertThatThrownBy(() -> source.findReport(Ssn.parse("123-45-6789")))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining("123456789");
    }

    @Test
    void reconnectsAfterAFailure() {
        failing.set(true);
        assertThatThrownBy(() -> source.findReport(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
        failing.set(false);
        assertThat(source.findReport(Ssn.parse("123-45-6789"))).isPresent();
    }

    @Test
    void aBriefRefusalIsRetriedWithBackoffAndRecovers() {
        source.findReport(Ssn.parse("123-45-6789")); // connect first, so only the read is refused
        failNext.set(2); // 503 twice, then the third try succeeds
        assertThat(source.findReport(Ssn.parse("123-45-6789"))).isPresent();

        refuseWith = 429;
        failNext.set(2);
        assertThat(source.findReport(Ssn.parse("123-45-6789"))).isPresent();
    }

    @Test
    void a500IsNotRetried() {
        source.findReport(Ssn.parse("123-45-6789"));
        int before = requests.get();
        failing.set(true);
        assertThatThrownBy(() -> source.findReport(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
        // One failed read, plus at most the reconnect's own initialise request.
        assertThat(requests.get() - before).isLessThanOrEqualTo(2);
    }

    @Test
    void aTimeoutIsNotRetriedBecauseTheServerMayStillBeWorking() {
        McpCreditReportSource quick = new McpCreditReportSource(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "test", Duration.ofMillis(300),
                FAST_RETRY, Duration.ofMillis(1), json);
        try {
            quick.findReport(Ssn.parse("123-45-6789"));
            hang.set(true);
            int before = requests.get();
            assertThatThrownBy(() -> quick.findReport(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
            assertThat(requests.get() - before).isEqualTo(1);
        } finally {
            hang.set(false);
            quick.close();
        }
    }

    @Test
    void whatIsRetriedIsDecidedByTheCauseChain() {
        assertThat(McpCreditReportSource.isRetryable(new RuntimeException(new java.net.ConnectException("refused")))).isTrue();
        assertThat(McpCreditReportSource.isRetryable(new RuntimeException(new dev.langchain4j.exception.HttpException(503, "x")))).isTrue();
        assertThat(McpCreditReportSource.isRetryable(new RuntimeException(new dev.langchain4j.exception.HttpException(500, "x")))).isFalse();
        assertThat(McpCreditReportSource.isRetryable(new RuntimeException(new java.util.concurrent.TimeoutException()))).isFalse();
        assertThat(McpCreditReportSource.isRetryable(new IllegalArgumentException("bug"))).isFalse();
    }

    @Test
    void anOutageLongerThanTheRetriesFails() {
        source.findReport(Ssn.parse("123-45-6789"));
        failNext.set(1_000);
        assertThatThrownBy(() -> source.findReport(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void anMcpErrorAnswerIsNotRetried() {
        assertThat(source.findReport(Ssn.parse("234-56-7890"))).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void resourceUriUsesTheDigits() {
        assertThat(McpCreditReportSource.resourceUri(Ssn.parse("123-45-6789"))).isEqualTo("credit-report://ssn/123456789");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            if (hang.get()) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (failing.get()) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.sendResponseHeaders(refuseWith, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", id);
            switch (request.path("method").asString()) {
                case "initialize" -> {
                    ObjectNode result = response.putObject("result");
                    result.put("protocolVersion", "2025-11-25");
                    result.putObject("capabilities").putObject("resources");
                    result.putObject("serverInfo").put("name", "fake").put("version", "1");
                }
                case "resources/read" -> {
                    reads.incrementAndGet();
                    String uri = request.at("/params/uri").asString();
                    if (uri.equals("credit-report://ssn/123456789")) {
                        response.putObject("result").putArray("contents").addObject()
                                .put("uri", uri).put("mimeType", "application/json").put("text", REPORT_JSON);
                    } else {
                        response.putObject("error").put("code", -32002).put("message", "Resource not found");
                    }
                }
                default -> response.putObject("result");
            }
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
