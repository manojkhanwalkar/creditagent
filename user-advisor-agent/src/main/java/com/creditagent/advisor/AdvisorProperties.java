package com.creditagent.advisor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The User Advisor Agent ({@code credit.advisor.*}).
 *
 * @param serviceToken the internal API token ({@code INTERNAL_API_TOKEN}) the web server presents.
 *     Blank turns the API off (404): this agent is never meant to be called by anyone else.
 * @param memoryTtl a conversation not used for this long is forgotten (default 5 minutes, the web
 *     server's session idle timeout)
 * @param maxConversations conversations kept at once; past it the least recently used is dropped
 * @param maxTurns question-and-answer pairs kept per conversation (the oldest are dropped)
 * @param memoryPressure share of the maximum heap in use above which the least recently used half of
 *     the conversations is dropped
 * @param maxPromptChars longest question accepted
 * @param maxReplyChars longest answer returned, disclaimer excluded
 */
@ConfigurationProperties("credit.advisor")
public record AdvisorProperties(
        String serviceToken,
        Duration memoryTtl,
        Integer maxConversations,
        Integer maxTurns,
        Double memoryPressure,
        Integer maxPromptChars,
        Integer maxReplyChars) {

    public AdvisorProperties {
        serviceToken = serviceToken == null || serviceToken.isBlank() ? null : serviceToken;
        memoryTtl = memoryTtl == null ? Duration.ofMinutes(5) : memoryTtl;
        maxConversations = maxConversations == null ? 10_000 : maxConversations;
        maxTurns = maxTurns == null ? 20 : maxTurns;
        memoryPressure = memoryPressure == null ? 0.85 : memoryPressure;
        maxPromptChars = maxPromptChars == null ? 1000 : maxPromptChars;
        maxReplyChars = maxReplyChars == null ? 2000 : maxReplyChars;
        if (!memoryTtl.isPositive() || maxConversations < 1 || maxTurns < 1 || memoryPressure <= 0 || memoryPressure > 1
                || maxPromptChars < 1 || maxReplyChars < 1) {
            throw new IllegalArgumentException("credit.advisor: limits must be positive and memory-pressure in (0, 1]");
        }
    }

    @Override
    public String toString() {
        return "AdvisorProperties[serviceToken=<redacted>, memoryTtl=" + memoryTtl + ", maxConversations=" + maxConversations
                + ", maxTurns=" + maxTurns + "]";
    }
}
