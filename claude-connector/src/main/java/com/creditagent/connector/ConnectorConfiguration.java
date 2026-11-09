package com.creditagent.connector;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.McpCreditReportSource;
import com.creditagent.clients.RetrySettings;
import com.creditagent.clients.ScoreCalculatorHttpClient;
import com.creditagent.clients.ScoreService;
import com.creditagent.web.support.KeyedRateLimiter;
import com.creditagent.web.support.RequestSizeLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectorProperties.class)
class ConnectorConfiguration {

    /** Users tracked by the per-user limiter before ended windows are swept. */
    static final int MAX_TRACKED_USERS = 100_000;

    /** Refuses an oversized {@code /mcp} body before Spring reads it into memory. */
    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> mcpRequestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(McpHttpController.MAX_BODY_BYTES, McpHttpController.MCP_PATH);
    }

    @Bean
    KeyedRateLimiter perUserLimiter(ConnectorProperties properties) {
        return new KeyedRateLimiter(properties.requestsPerUserPerMinute(), MAX_TRACKED_USERS, System::currentTimeMillis);
    }

    @Bean
    AccountClient accountClient(RestClient.Builder builder, ConnectorProperties properties, RetrySettings retry) {
        return AccountClient.create(builder, properties, retry);
    }

    @Bean
    AdviceClient adviceClient(RestClient.Builder builder, ConnectorProperties properties, RetrySettings retry) {
        return AdviceClient.create(builder, properties, retry);
    }

    /**
     * Reads with the service token: the connector reads only the token holder's own report, which the
     * owner may always see, so the MCP server does not lock-check it (as for the web UI).
     */
    @Bean // AutoCloseable: Spring closes it on shutdown
    McpCreditReportSource creditReportSource(ConnectorProperties properties, JsonMapper jsonMapper, RetrySettings retry) {
        return new McpCreditReportSource(properties.mcpUrl(), "claude-connector", properties.mcpTimeout(), retry,
                properties.serviceToken(), jsonMapper);
    }

    @Bean
    ScoreService scoreService(RestClient.Builder builder, ConnectorProperties properties, RetrySettings retry) {
        return new ScoreCalculatorHttpClient(
                HttpClients.restClient(builder, properties.scoreCalculatorUrl(), properties.timeout(), retry));
    }

    @Bean
    CreditTools creditTools(CreditReportSource reports, ScoreService scores, AdviceClient advice, AccountClient accounts) {
        return new CreditTools(reports, scores, advice, accounts);
    }

    @Bean
    ConnectorMcpHandler connectorMcpHandler(CreditTools tools, JsonMapper jsonMapper) {
        return new ConnectorMcpHandler(tools, jsonMapper);
    }
}
