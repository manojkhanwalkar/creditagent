package com.creditagent.web.support;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound limits for every service ({@code credit.inbound.*}). See {@link InboundLimitFilter}.
 *
 * @param enabled turn the filter off entirely
 * @param ratePerSecond long-run requests per second the service admits (token refill rate)
 * @param burst requests admitted at once after a quiet spell (bucket capacity)
 * @param maxConcurrent requests in progress at once; 0 for no limit
 * @param urlPatterns servlet URL patterns the limits apply to; default {@code /*}. A service that
 *     also serves static files can narrow this (the web server uses {@code /api/*}), so page loads
 *     do not use up the bucket.
 */
@ConfigurationProperties("credit.inbound")
public record InboundLimitProperties(
        Boolean enabled, Double ratePerSecond, Integer burst, Integer maxConcurrent, List<String> urlPatterns) {

    public static final double DEFAULT_RATE_PER_SECOND = 50;
    public static final int DEFAULT_BURST = 100;
    public static final int DEFAULT_MAX_CONCURRENT = 32;

    public InboundLimitProperties {
        enabled = enabled == null || enabled;
        ratePerSecond = ratePerSecond == null ? DEFAULT_RATE_PER_SECOND : ratePerSecond;
        burst = burst == null ? DEFAULT_BURST : burst;
        maxConcurrent = maxConcurrent == null ? DEFAULT_MAX_CONCURRENT : maxConcurrent;
        urlPatterns = urlPatterns == null || urlPatterns.isEmpty() ? List.of("/*") : List.copyOf(urlPatterns);
        if (ratePerSecond <= 0 || burst < 1 || maxConcurrent < 0) {
            throw new IllegalArgumentException(
                    "credit.inbound: rate-per-second must be positive, burst at least 1, max-concurrent 0 or more");
        }
    }
}
