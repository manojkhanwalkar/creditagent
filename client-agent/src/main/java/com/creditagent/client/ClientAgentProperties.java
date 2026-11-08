package com.creditagent.client;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param intelligenceAgentUrl base URL of the Credit Intelligence Agent (its agent card is fetched from here)
 * @param timeout longest wait for the agent's answer; it may call an LLM, so this is longer than the
 *     other services' timeouts. Defaults to 60 s.
 */
@Validated
@ConfigurationProperties("credit.client")
public record ClientAgentProperties(@NotBlank String intelligenceAgentUrl, Duration timeout) {

    public ClientAgentProperties {
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }
}
