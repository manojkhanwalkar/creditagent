package com.creditagent.web.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The chat ({@code credit.chat.*}).
 *
 * @param advisorUrl the User Advisor Agent, e.g. {@code http://localhost:8089}
 * @param timeout longest wait for one answer; the placeholder answers in milliseconds, an LLM may take
 *     seconds. Defaults to 15 s.
 */
@ConfigurationProperties("credit.chat")
public record ChatProperties(String advisorUrl, Duration timeout) {

    public ChatProperties {
        advisorUrl = advisorUrl == null || advisorUrl.isBlank() ? "http://localhost:8089" : advisorUrl;
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }
}
