package com.creditagent.agent;

import com.creditagent.agent.advice.CreditAdvisor;
import com.creditagent.agent.advice.LlmCreditAdvisor;
import com.creditagent.agent.advice.RuleBasedCreditAdvisor;
import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.McpCreditReportSource;
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
    McpCreditReportSource creditReportSource(AgentProperties properties, JsonMapper jsonMapper) {
        return new McpCreditReportSource(
                properties.mcpUrl(), "credit-intelligence-agent", properties.timeout(), jsonMapper);
    }

    @Bean
    ScoreService scoreService(RestClient.Builder builder, AgentProperties properties) {
        return new ScoreCalculatorHttpClient(
                HttpClients.restClient(builder, properties.scoreCalculatorUrl(), properties.timeout()));
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
                .timeout(properties.timeout().multipliedBy(6))
                .logRequests(false)
                .logResponses(false)
                .build();
        return new LlmCreditAdvisor(model, jsonMapper, rules);
    }

    @Bean
    CreditIntelligenceService creditIntelligenceService(
            CreditReportSource reports, ScoreService scores, CreditAdvisor advisor) {
        return new CreditIntelligenceService(reports, scores, advisor);
    }
}
