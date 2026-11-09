package com.creditagent.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param timeout connect/read timeout for the Score Calculator
 * @param mcpTimeout longest wait for one MCP look-up, reconnects and retries included; covers the MCP server's own NRG retries. 20 s
 * @param publicUrl URL advertised in the A2A agent card; defaults to {@code http://localhost:<port>}
 * @param llm optional LLM settings; when disabled the agent uses deterministic rules
 */
@Validated
@ConfigurationProperties("credit.agent")
public record AgentProperties(
        @NotBlank String mcpUrl,
        @NotBlank String scoreCalculatorUrl,
        @NotNull Duration timeout,
        String publicUrl,
        @Valid Llm llm,
        Duration mcpTimeout) {

    public AgentProperties {
        mcpTimeout = mcpTimeout == null ? Duration.ofSeconds(20) : mcpTimeout;
    }


    /**
     * @param timeout longest wait for the model's answer; defaults to 30 s. The call is not retried:
     *     on any failure the agent answers with its rules at once, which keeps the whole analysis
     *     inside the client agent's wait (see docs/architecture.md, Resilience)
     */
    public record Llm(boolean enabled, String model, String apiKey, Duration timeout) {
        public Llm {
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            if (enabled && (apiKey == null || apiKey.isBlank())) {
                throw new IllegalArgumentException("credit.agent.llm.api-key is required when the LLM is enabled");
            }
        }

        @Override
        public String toString() {
            return "Llm[enabled=" + enabled + ", model=" + model + ", apiKey=<redacted>, timeout=" + timeout + "]";
        }
    }
}
