package com.creditagent.mcp;

import com.creditagent.clients.HttpClients;
import com.creditagent.common.CreditFileNames;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({McpServerProperties.class, NrgProperties.class})
class McpServerConfiguration {

    static final String SERVER_NAME = "credit-report-mcp";
    static final String SERVER_VERSION = "0.1.0";

    @Bean
    CreditFileNames creditFileNames(McpServerProperties properties) {
        return new CreditFileNames(properties.hmacKey());
    }

    @Bean
    ReportNormalizerClient reportNormalizerClient(RestClient.Builder builder, NrgProperties properties) {
        return new NrgHttpClient(HttpClients.restClient(builder, properties.url(), properties.timeout()));
    }

    @Bean
    McpImplementation mcpServerInfo() {
        McpImplementation info = new McpImplementation();
        info.setName(SERVER_NAME);
        info.setVersion(SERVER_VERSION);
        return info;
    }

    /** LangChain4j's MCP server handles {@code tools/list} and {@code tools/call}. */
    @Bean
    McpServer langchain4jMcpServer(CreditReportTools tools, McpImplementation serverInfo) {
        return new McpServer(List.of(tools), serverInfo);
    }
}
