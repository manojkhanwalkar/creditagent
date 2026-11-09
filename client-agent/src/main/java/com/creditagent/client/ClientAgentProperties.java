package com.creditagent.client;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param intelligenceAgentUrl base URL of the Credit Intelligence Agent (its agent card is fetched from here)
 * @param timeout longest wait for the agent's answer. Defaults to 75 s: longer than the agent's own
 *     worst case (MCP look-up 20 s + Score Calculator with retries ~17.4 s + LLM 30 s, about 67 s), so
 *     the client never gives up on an answer the agent is still completing.
 */
@Validated
@ConfigurationProperties("credit.client")
public record ClientAgentProperties(@NotBlank String intelligenceAgentUrl, Duration timeout) {

    public ClientAgentProperties {
        timeout = timeout == null ? Duration.ofSeconds(75) : timeout;
    }
}
