package com.creditagent.web;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.McpCreditReportSource;
import com.creditagent.clients.ScoreCalculatorHttpClient;
import com.creditagent.clients.ScoreService;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebServerProperties.class)
class WebServerConfiguration {

    @Bean // AutoCloseable: Spring closes it on shutdown
    McpCreditReportSource creditReportSource(WebServerProperties properties, JsonMapper jsonMapper) {
        return new McpCreditReportSource(properties.mcpUrl(), "web-server", properties.timeout(), jsonMapper);
    }

    @Bean
    ScoreService scoreService(RestClient.Builder builder, WebServerProperties properties) {
        return new ScoreCalculatorHttpClient(
                HttpClients.restClient(builder, properties.scoreCalculatorUrl(), properties.timeout()));
    }

    @Bean
    CreditScoreService creditScoreService(CreditReportSource reports, ScoreService scores) {
        return new CreditScoreService(reports, scores);
    }
}
