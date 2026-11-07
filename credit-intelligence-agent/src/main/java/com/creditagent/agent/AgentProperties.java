package com.creditagent.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
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
        @Valid Llm llm) {

    public record Llm(boolean enabled, String model, String apiKey) {
        public Llm {
            if (enabled && (apiKey == null || apiKey.isBlank())) {
                throw new IllegalArgumentException("credit.agent.llm.api-key is required when the LLM is enabled");
            }
        }

        @Override
        public String toString() {
            return "Llm[enabled=" + enabled + ", model=" + model + ", apiKey=<redacted>]";
        }
    }
}
