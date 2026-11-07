package com.creditagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.Ssn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditReportMcpHandlerTest {

    static final String KEY = "test-key-0123456789abcdef0123456789abcdef";
    static final String REPORT = "{\"reportId\":\"r-1\",\"ssnMasked\":\"***-**-6789\"}";

    @TempDir
    Path dir;

    final ObjectMapper mapper = new ObjectMapper();
    CreditReportMcpHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        CreditFileNames names = new CreditFileNames(KEY);
        Files.writeString(dir.resolve(names.fileNameFor(Ssn.parse("123-45-6789"))), REPORT);
        CreditFileRepository repository = new CreditFileRepository(new McpServerProperties(dir, KEY, List.of()), names);
        McpImplementation info = new McpImplementation("credit-report-mcp", "test");
        handler = new CreditReportMcpHandler(
                new McpServer(List.of(new CreditReportTools(repository)), info), info, repository);
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
    void readsAReportByResourceUri() throws IOException {
        JsonNode content = call("""
                {"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"credit-report://ssn/123456789"}}
                """).at("/result/contents/0");
        assertThat(content.get("text").asText()).isEqualTo(REPORT);
        assertThat(content.get("mimeType").asText()).isEqualTo("application/json");
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
}
