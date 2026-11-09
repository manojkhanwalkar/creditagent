package com.creditagent.agent;

import com.creditagent.agent.advice.CreditAdvisor;
import com.creditagent.agent.advice.LlmCreditAdvisor;
import com.creditagent.agent.advice.RuleBasedCreditAdvisor;
import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.McpCreditReportSource;
import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ReportLockHttpClient;
import com.creditagent.clients.ReportLockProperties;
import com.creditagent.clients.RetrySettings;
import com.creditagent.clients.ScoreCalculatorHttpClient;
import com.creditagent.clients.ScoreService;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
class AgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

    @Bean // AutoCloseable: Spring closes it on shutdown
    McpCreditReportSource creditReportSource(
            AgentProperties properties, JsonMapper jsonMapper, RetrySettings retry, ReportLockProperties lock) {
        // The agent checks the lock itself before every read, so it identifies itself to the MCP
        // server with the service token and is not checked a second time.
        return new McpCreditReportSource(properties.mcpUrl(), "credit-intelligence-agent", properties.mcpTimeout(),
                retry, lock.token(), jsonMapper);
    }

    @Bean
    ScoreService scoreService(RestClient.Builder builder, AgentProperties properties, RetrySettings retry) {
        return new ScoreCalculatorHttpClient(
                HttpClients.restClient(builder, properties.scoreCalculatorUrl(), properties.timeout(), retry));
    }

    @Bean
    CreditAdvisor creditAdvisor(AgentProperties properties, JsonMapper jsonMapper) {
        RuleBasedCreditAdvisor rules = new RuleBasedCreditAdvisor();
        AgentProperties.Llm llm = properties.llm();
        if (llm == null || !llm.enabled()) {
            log.info("Using rule-based credit advice (LLM disabled)");
            return rules;
        }
        log.info("Using LLM credit advice with model {}", llm.model());
        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey(llm.apiKey())
                .modelName(llm.model())
                .maxTokens(2048)
                .timeout(llm.timeout())
                // No retries: a slow model is not retried after a timeout, and any failure falls
                // back to the rules at once (LangChain4j would otherwise make up to 3 tries).
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
        return new LlmCreditAdvisor(model, jsonMapper, rules);
    }

    @Bean
    ReportLockCheck reportLockCheck(RestClient.Builder builder, ReportLockProperties properties, RetrySettings retry) {
        if (!properties.hasToken()) {
            log.warn("INTERNAL_API_TOKEN is not set: every report-lock check fails, so all advice requests are refused");
        }
        return ReportLockHttpClient.create(builder, properties, retry);
    }

    @Bean
    CreditIntelligenceService creditIntelligenceService(
            CreditReportSource reports, ScoreService scores, CreditAdvisor advisor, ReportLockCheck locks) {
        return new CreditIntelligenceService(reports, scores, advisor, locks);
    }
}
