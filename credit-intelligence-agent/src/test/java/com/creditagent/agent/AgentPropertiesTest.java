package com.creditagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.clients.RetrySettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentPropertiesTest {

    @Test
    void anMcpReadOutlastsTheMcpServersRetriesOfNrg() {
        AgentProperties properties = new AgentProperties("http://mcp", "http://score", Duration.ofSeconds(5), null, null, null);
        assertThat(properties.mcpTimeout()).isGreaterThan(RetrySettings.DEFAULTS.worstCase(Duration.ofSeconds(5)));
    }

    @Test
    void theLlmGetsOneTryWithItsOwnTimeout() {
        AgentProperties.Llm llm = new AgentProperties.Llm(false, "m", null, null);
        assertThat(llm.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(llm.toString()).doesNotContain("apiKey=null").contains("<redacted>");
    }
}
