package com.creditagent.mcp;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ReportLockHttpClient;
import com.creditagent.clients.ReportLockProperties;
import com.creditagent.web.support.ServiceToken;
import com.creditagent.clients.RetrySettings;
import com.creditagent.common.CreditFileNames;
import com.creditagent.web.support.RequestSizeLimitFilter;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
    ReportNormalizerClient reportNormalizerClient(RestClient.Builder builder, NrgProperties properties, RetrySettings retry) {
        // Normalizing is a pure computation, so any transient failure is retried.
        return new NrgHttpClient(HttpClients.restClient(builder, properties.url(), properties.timeout(), retry));
    }

    /**
     * Refuses an oversized {@code /mcp} body before Spring reads it into memory. The controller's own
     * length check only ran after the whole body had been buffered.
     */
    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> mcpRequestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(McpHttpController.MAX_BODY_BYTES, "/mcp");
    }

    /** The get_credit_report tool checks report locks with the web server (fail closed). */
    @Bean
    ReportLockCheck reportLockCheck(RestClient.Builder builder, ReportLockProperties properties, RetrySettings retry) {
        return ReportLockHttpClient.create(builder, properties, retry);
    }

    /** Identifies internal callers (web server, agent); the same INTERNAL_API_TOKEN as the lock check. */
    @Bean
    ServiceToken serviceToken(ReportLockProperties properties) {
        return new ServiceToken(properties.token());
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
