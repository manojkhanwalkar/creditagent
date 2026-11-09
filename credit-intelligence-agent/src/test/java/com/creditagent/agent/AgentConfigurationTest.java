package com.creditagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.agent.advice.LlmCreditAdvisor;
import com.creditagent.agent.advice.RuleBasedCreditAdvisor;
import com.creditagent.clients.ReportLockProperties;
import com.creditagent.clients.RetrySettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class AgentConfigurationTest {

    final AgentConfiguration configuration = new AgentConfiguration();
    final JsonMapper json = JsonMapper.builder().build();

    AgentProperties properties(AgentProperties.Llm llm) {
        return new AgentProperties("http://mcp.invalid/mcp", "http://score.invalid", Duration.ofSeconds(5), null, llm, null);
    }

    @Test
    void withoutAnLlmTheRulesAdvise() {
        assertThat(configuration.creditAdvisor(properties(null), json)).isInstanceOf(RuleBasedCreditAdvisor.class);
        assertThat(configuration.creditAdvisor(properties(new AgentProperties.Llm(false, "m", null, null)), json))
                .isInstanceOf(RuleBasedCreditAdvisor.class);
    }

    @Test
    void withAnLlmTheModelAdvisesAndTheRulesAreTheFallback() {
        // Building the model makes no network call.
        AgentProperties.Llm llm = new AgentProperties.Llm(true, "claude-sonnet-5-5", "test-key", Duration.ofSeconds(30));
        assertThat(configuration.creditAdvisor(properties(llm), json)).isInstanceOf(LlmCreditAdvisor.class);
    }

    @Test
    void anEnabledLlmNeedsAKey() {
        assertThatThrownBy(() -> new AgentProperties.Llm(true, "m", " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProperties.Llm(true, "m", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clientsAreWiredWithTheirSettings() throws Exception {
        try (var mcp = configuration.creditReportSource(properties(null), json, RetrySettings.DEFAULTS,
                new ReportLockProperties(null, "t".repeat(32), null))) {
            assertThat(mcp).isNotNull();
        }
        assertThat(configuration.scoreService(RestClient.builder(), properties(null), RetrySettings.DEFAULTS)).isNotNull();
        assertThat(configuration.reportLockCheck(RestClient.builder(), new ReportLockProperties(null, null, null), RetrySettings.DEFAULTS))
                .isNotNull();
        assertThat(configuration.reportLockCheck(RestClient.builder(), new ReportLockProperties(null, "t".repeat(32), null), RetrySettings.DEFAULTS))
                .isNotNull();
    }
}
