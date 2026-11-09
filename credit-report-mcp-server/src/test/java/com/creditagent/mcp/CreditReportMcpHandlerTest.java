package com.creditagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditReportMcpHandlerTest {

    static final String KEY = "test-key-0123456789abcdef0123456789abcdef";
    static final String REPORT = "{\"reportId\":\"r-1\",\"ssnMasked\":\"***-**-6789\"}";
    static final String EQUIFAX = "{\"reportNumber\":\"EFX-1\"}";
    static final String TRANSUNION = "{\"fileNumber\":\"TU-1\"}";

    @TempDir
    Path dir;

    final ObjectMapper mapper = new ObjectMapper();
    CreditReportMcpHandler handler;
    final AtomicReference<Map<Bureau, String>> sentToNrg = new AtomicReference<>();
    /** What the report-lock check answers: "locked", "unlocked" or "down". */
    final AtomicReference<String> lock = new AtomicReference<>("unlocked");

    @BeforeEach
    void setUp() throws IOException {
        CreditFileNames names = new CreditFileNames(KEY);
        String fileName = names.fileNameFor(Ssn.parse("123-45-6789"));
        Files.createDirectories(dir.resolve("Equifax"));
        Files.createDirectories(dir.resolve("TransUnion"));
        Files.writeString(dir.resolve("Equifax").resolve(fileName), EQUIFAX);
        Files.writeString(dir.resolve("TransUnion").resolve(fileName), TRANSUNION);
        CreditFileRepository repository = new CreditFileRepository(new McpServerProperties(dir, KEY, List.of()), names);
        CreditReportService reports = new CreditReportService(repository, bureauReports -> {
            sentToNrg.set(bureauReports);
            return REPORT;
        });
        McpImplementation info = new McpImplementation("credit-report-mcp", "test");
        handler = new CreditReportMcpHandler(
                new McpServer(List.of(new CreditReportTools(reports, lockCheck())), info), info, reports, lockCheck());
    }

    com.creditagent.clients.ReportLockCheck lockCheck() {
        return ssn -> switch (lock.get()) {
            case "locked" -> true;
            case "down" -> throw new com.creditagent.clients.ServiceUnavailableException("down", null);
            default -> false;
        };
    }

    JsonNode call(String json) throws IOException {
        Optional<String> response = handler.handle(json);
        assertThat(response).isPresent();
        return mapper.readTree(response.get());
    }

    @Test
    void initializeAdvertisesToolsAndResources() throws IOException {
        JsonNode result = call("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
                """).get("result");
        assertThat(result.get("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(result.at("/capabilities/tools").isObject()).isTrue();
        assertThat(result.at("/capabilities/resources").isObject()).isTrue();
        assertThat(result.at("/serverInfo/name").asText()).isEqualTo("credit-report-mcp");
    }

    @Test
    void unknownProtocolVersionFallsBackToTheDefault() throws IOException {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertThat(result.at("/result/protocolVersion").asText()).isEqualTo(CreditReportMcpHandler.DEFAULT_PROTOCOL_VERSION);
    }

    @Test
    void notificationsGetNoResponse() {
        assertThat(handler.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isEmpty();
    }

    @Test
    void readsEveryBureauFileAndReturnsTheNormalizedReport() throws IOException {
        JsonNode content = call("""
                {"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"credit-report://ssn/123456789"}}
                """).at("/result/contents/0");
        assertThat(content.get("text").asText()).isEqualTo(REPORT);
        assertThat(content.get("mimeType").asText()).isEqualTo("application/json");
        assertThat(sentToNrg.get()).containsExactly(
                Map.entry(Bureau.EQUIFAX, EQUIFAX), Map.entry(Bureau.TRANSUNION, TRANSUNION));
    }

    @Test
    void dashedSsnInUriAlsoWorks() throws IOException {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/read\",\"params\":{\"uri\":\"credit-report://ssn/123-45-6789\"}}");
        assertThat(response.at("/result/contents/0/text").asText()).isEqualTo(REPORT);
    }

    @Test
    void missingReportIsResourceNotFoundWithoutEchoingTheSsn() throws IOException {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"credit-report://ssn/234567890\"}}");
        assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.RESOURCE_NOT_FOUND);
        assertThat(response.toString()).doesNotContain("234567890");
        assertThat(sentToNrg.get()).isNull();
    }

    @Test
    void pathTraversalStyleUrisAreRejected() throws IOException {
        for (String uri : List.of("credit-report://ssn/../../etc/passwd", "file:///etc/passwd", "credit-report://ssn/%2e%2e")) {
            JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/read\",\"params\":{\"uri\":\"" + uri + "\"}}");
            assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.INVALID_PARAMS);
        }
    }

    @Test
    void invalidSsnInUriIsInvalidParams() throws IOException {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/read\",\"params\":{\"uri\":\"credit-report://ssn/000-12-3456\"}}");
        assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.INVALID_PARAMS);
    }

    @Test
    void resourceListDoesNotEnumerateCreditFiles() throws IOException {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}");
        assertThat(response.at("/result/resources").isEmpty()).isTrue();
    }

    @Test
    void templatesDescribeTheResource() throws IOException {
        JsonNode template = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/templates/list\"}")
                .at("/result/resourceTemplates/0");
        assertThat(template.get("uriTemplate").asText()).isEqualTo("credit-report://ssn/{ssn}");
    }

    @Test
    void toolsAreServedByLangChain4j() throws IOException {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}").at("/result/tools");
        assertThat(tools.findValuesAsText("name")).contains("get_credit_report");

        JsonNode result = call("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                 "params":{"name":"get_credit_report","arguments":{"ssn":"123-45-6789"}}}
                """);
        assertThat(result.at("/result/content/0/text").asText()).isEqualTo(REPORT);
    }

    @Test
    void protocolErrors() throws IOException {
        assertThat(call("{not json").at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.PARSE_ERROR);
        assertThat(call("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]").at("/error/code").asInt())
                .isEqualTo(CreditReportMcpHandler.INVALID_REQUEST);
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}").at("/error/code").asInt())
                .isEqualTo(CreditReportMcpHandler.METHOD_NOT_FOUND);
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").get("result").isObject()).isTrue();
    }

    JsonNode callTool() throws IOException {
        return call("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call",
                 "params":{"name":"get_credit_report","arguments":{"ssn":"123-45-6789"}}}
                """);
    }

    @Test
    void theToolRefusesALockedReportWithoutReadingIt() throws IOException {
        lock.set("locked");
        assertThat(callTool().toString()).contains(CreditReportTools.LOCKED).doesNotContain("reportId");
        assertThat(sentToNrg.get()).isNull();
    }

    @Test
    void theToolRefusesWhenTheLockCannotBeChecked() throws IOException {
        lock.set("down");
        assertThat(callTool().toString()).contains(CreditReportTools.LOCK_UNAVAILABLE);
        assertThat(sentToNrg.get()).isNull();
    }

    static final String READ = """
            {"jsonrpc":"2.0","id":8,"method":"resources/read","params":{"uri":"credit-report://ssn/123456789"}}
            """;

    @Test
    void aThirdPartyResourceReadOfALockedReportIsRefused() throws IOException {
        lock.set("locked");
        JsonNode response = call(READ); // no service token: a generic MCP host
        assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.REPORT_LOCKED);
        assertThat(response.at("/error/message").asText()).isEqualTo("This credit report is locked by its owner.");
        assertThat(response.toString()).doesNotContain("123456789").doesNotContain("reportId");
        assertThat(sentToNrg.get()).isNull();
    }

    @Test
    void aThirdPartyReadFailsClosedWhenTheLockCannotBeChecked() throws IOException {
        lock.set("down");
        JsonNode response = call(READ);
        assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.INTERNAL_ERROR);
        assertThat(sentToNrg.get()).isNull();
    }

    @Test
    void internalCallersAreNotCheckedAgain() throws IOException {
        // The web server reads for the signed-in owner; the agent has checked the lock itself.
        lock.set("down");
        JsonNode response = mapper.readTree(handler.handle(READ, true).orElseThrow());
        assertThat(response.has("result")).isTrue();
    }

    @Test
    void aRequestWithoutAMethodIsInvalid() throws IOException {
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":9}").at("/error/code").asInt())
                .isEqualTo(CreditReportMcpHandler.INVALID_REQUEST);
    }

    @Test
    void toolCallsNeedANumericId() throws IOException {
        JsonNode response = call("""
                {"jsonrpc":"2.0","id":"abc","method":"tools/call",
                 "params":{"name":"get_credit_report","arguments":{"ssn":"123-45-6789"}}}
                """);
        assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.INVALID_REQUEST);
    }

    @Test
    void theToolExplainsAnInvalidSsnWithoutEchoingIt() throws IOException {
        JsonNode response = call("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call",
                 "params":{"name":"get_credit_report","arguments":{"ssn":"000-12-3456"}}}
                """);
        assertThat(response.toString()).contains("area number").doesNotContain("3456");
        assertThat(sentToNrg.get()).isNull();
    }

    @Test
    void aFailingReadIsAnInternalErrorWithoutDetails() throws IOException {
        CreditReportService failing = new CreditReportService(
                new CreditFileRepository(new McpServerProperties(dir, KEY, null), new CreditFileNames(KEY)),
                bureauReports -> {
                    throw new IllegalStateException("NRG down at http://internal");
                });
        McpImplementation info = new McpImplementation("credit-report-mcp", "test");
        CreditReportMcpHandler broken = new CreditReportMcpHandler(
                new McpServer(List.of(new CreditReportTools(failing, ssn -> false)), info), info, failing, ssn -> false);
        JsonNode response = mapper.readTree(broken.handle(READ, true).orElseThrow());
        assertThat(response.at("/error/code").asInt()).isEqualTo(CreditReportMcpHandler.INTERNAL_ERROR);
        assertThat(response.toString()).doesNotContain("internal").doesNotContain("123456789");
    }

    @Test
    void theFileStoreRefusesOversizedFilesAndIgnoresSymlinks() throws IOException {
        CreditFileNames names = new CreditFileNames(KEY);
        CreditFileRepository repository = new CreditFileRepository(new McpServerProperties(dir, KEY, null), names);
        Ssn big = Ssn.parse("234-56-7890");
        Files.createDirectories(dir.resolve("Experian"));
        Files.write(dir.resolve("Experian").resolve(names.fileNameFor(big)), new byte[(int) CreditFileRepository.MAX_FILE_BYTES + 1]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findBureauReports(big))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("7890");

        Ssn linked = Ssn.parse("345-67-8901");
        Path target = Files.writeString(dir.resolve("elsewhere.json"), EQUIFAX);
        Files.createSymbolicLink(dir.resolve("Equifax").resolve(names.fileNameFor(linked)), target);
        assertThat(repository.findBureauReports(linked)).isEmpty();
    }

    @Test
    void theHmacKeyIsNeverPrinted() {
        McpServerProperties properties = new McpServerProperties(dir, KEY, null);
        assertThat(properties.allowedOrigins()).isEmpty();
        assertThat(properties.toString()).doesNotContain(KEY).contains("<redacted>");
    }
}
