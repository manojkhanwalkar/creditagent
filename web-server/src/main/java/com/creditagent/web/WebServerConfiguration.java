package com.creditagent.web;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.McpCreditReportSource;
import com.creditagent.clients.RetrySettings;
import com.creditagent.clients.ScoreCalculatorHttpClient;
import com.creditagent.clients.ScoreService;
import com.creditagent.web.support.RequestSizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebServerProperties.class)
class WebServerConfiguration {

    /**
     * Cap for the JSON endpoints (an SSN, or a user id and password). Registration is multipart and
     * has its own limits (spring.servlet.multipart.*), so it is not listed here.
     */
    static final long MAX_JSON_BYTES = 64 * 1024;

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> jsonRequestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_JSON_BYTES,
                "/api/v1/credit-score", "/api/v1/auth/login", "/api/v1/credit-report/status",
                "/internal/v1/report-locks/lookup");
    }

    @Bean // AutoCloseable: Spring closes it on shutdown
    McpCreditReportSource creditReportSource(WebServerProperties properties, JsonMapper jsonMapper, RetrySettings retry,
            @org.springframework.beans.factory.annotation.Value("${credit.internal.token:}") String serviceToken) {
        // The service token tells the MCP server this read is for the signed-in owner, so it is not
        // lock-checked: owners always see their own report.
        return new McpCreditReportSource(
                properties.mcpUrl(), "web-server", properties.mcpTimeout(), retry, serviceToken, jsonMapper);
    }

    @Bean
    ScoreService scoreService(RestClient.Builder builder, WebServerProperties properties, RetrySettings retry) {
        return new ScoreCalculatorHttpClient(
                HttpClients.restClient(builder, properties.scoreCalculatorUrl(), properties.timeout(), retry));
    }

    @Bean
    CreditScoreService creditScoreService(CreditReportSource reports, ScoreService scores) {
        return new CreditScoreService(reports, scores);
    }
}
