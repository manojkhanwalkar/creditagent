package com.creditagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.Ssn;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpException;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the real server and talks to it with the LangChain4j MCP client, exactly as the web server
 * and the intelligence agent do. If either side's protocol handling drifts, this fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpClientContractTest {

    static final String KEY = "contract-test-key-0123456789abcdef0123456789";
    static final String REPORT = "{\"reportId\":\"r-contract\",\"ssnMasked\":\"***-**-6789\"}";

    /** Stands in for the NRG service. */
    @TestConfiguration
    static class FakeNrg {
        @Bean
        @Primary
        ReportNormalizerClient fakeNrg() {
            return bureauReports -> REPORT;
        }

        /** One consumer with an Experian report; the real store needs DynamoDB. */
        @Bean
        @Primary
        com.creditagent.reports.CreditReportStore inMemoryReports() {
            InMemoryCreditReportStore store = new InMemoryCreditReportStore();
            store.replaceAll(Ssn.parse("123-45-6789"), java.util.Map.of(com.creditagent.common.bureau.Bureau.EXPERIAN, "{}"));
            return store;
        }

        @Bean
        @Primary
        com.creditagent.clients.ReportLockCheck unlocked() {
            return ssn -> false;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("credit.reports.ssn-hash-key", () -> KEY);
        registry.add("credit.reports.encryption-key", () -> KEY + "-encryption");
        registry.add("credit.reports.dynamodb-endpoint", () -> "http://localhost:1");
    }

    @LocalServerPort
    int port;

    McpClient client;

    @BeforeEach
    void connect() {
        client = DefaultMcpClient.builder()
                .key("contract-test")
                .transport(StreamableHttpMcpTransport.builder()
                        .url("http://localhost:" + port + "/mcp")
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .protocolVersion("2025-11-25")
                .build();
    }

    @AfterEach
    void close() throws Exception {
        client.close();
    }

    @Test
    void readsTheCreditFileResource() {
        McpReadResourceResult result = client.readResource("credit-report://ssn/123456789");
        assertThat(result.contents()).singleElement()
                .isInstanceOfSatisfying(McpTextResourceContents.class, text -> assertThat(text.text()).isEqualTo(REPORT));
    }

    @Test
    void unknownSsnSurfacesAsResourceNotFound() {
        assertThatThrownBy(() -> client.readResource("credit-report://ssn/234567890"))
                .satisfies(e -> assertThat(rootMcpError(e).errorCode()).isEqualTo(-32002));
    }

    @Test
    void listsTheResourceTemplate() {
        assertThat(client.listResourceTemplates()).singleElement()
                .satisfies(t -> assertThat(t.uriTemplate()).isEqualTo("credit-report://ssn/{ssn}"));
        assertThat(client.listResources()).isEmpty();
    }

    @Test
    void exposesTheLangChain4jTool() {
        assertThat(client.listTools()).anySatisfy(t -> assertThat(t.name()).isEqualTo("get_credit_report"));
        String text = client.executeTool(ToolExecutionRequest.builder()
                        .id("1")
                        .name("get_credit_report")
                        .arguments("{\"ssn\":\"123-45-6789\"}")
                        .build())
                .resultText();
        assertThat(text).isEqualTo(REPORT);
    }

    static McpException rootMcpError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof McpException e) {
                return e;
            }
        }
        throw new AssertionError("No McpException in cause chain", t);
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() {
        String huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
                + "x".repeat((int) McpHttpController.MAX_BODY_BYTES) + "\"}}";
        int status = org.springframework.web.client.RestClient.create().post()
                .uri("http://localhost:" + port + "/mcp")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(huge)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(413);
    }
}
