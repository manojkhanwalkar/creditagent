package com.creditagent.clients;

import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.retry.RetryPolicy;

/**
 * How service-to-service calls retry recoverable failures ({@code credit.retry.*}): exponential
 * backoff with jitter, using Spring Framework's {@link RetryPolicy}.
 *
 * <p>With the defaults, a call is tried at most 3 times. The waits before the 2nd and 3rd tries are
 * about 100 ms and 200 ms, each moved at random by up to 50 ms (the jitter) but never below the
 * base delay (Spring's rule) nor above 1 s. Jitter spreads out the retries of many callers that
 * failed at the same moment, so they do not all hit the recovering service together.
 *
 * @param maxAttempts tries in all, including the first; 1 turns retries off
 * @param initialDelay wait before the first retry
 * @param multiplier growth of the wait between retries
 * @param maxDelay longest single wait
 * @param jitter random amount added to or taken from each wait
 */
@ConfigurationProperties("credit.retry")
public record RetrySettings(
        Integer maxAttempts, Duration initialDelay, Double multiplier, Duration maxDelay, Duration jitter) {

    public static final RetrySettings DEFAULTS = new RetrySettings(null, null, null, null, null);

    public RetrySettings {
        maxAttempts = maxAttempts == null ? 3 : maxAttempts;
        initialDelay = initialDelay == null ? Duration.ofMillis(100) : initialDelay;
        multiplier = multiplier == null ? 2.0 : multiplier;
        maxDelay = maxDelay == null ? Duration.ofSeconds(1) : maxDelay;
        jitter = jitter == null ? Duration.ofMillis(50) : jitter;
        if (maxAttempts < 1 || multiplier < 1 || initialDelay.isNegative() || jitter.isNegative()
                || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("credit.retry: max-attempts must be at least 1, multiplier at least 1, "
                    + "delays and jitter non-negative, and max-delay at least initial-delay");
        }
    }

    /** No retries: every call is tried once. */
    public static RetrySettings none() {
        return new RetrySettings(1, Duration.ZERO, 1.0, Duration.ZERO, Duration.ZERO);
    }

    /** A Spring retry policy that retries only failures {@code recoverable} accepts. */
    public RetryPolicy policy(Predicate<Throwable> recoverable) {
        return RetryPolicy.builder()
                .maxRetries(maxAttempts - 1)
                .delay(initialDelay)
                .multiplier(multiplier)
                .maxDelay(maxDelay)
                .jitter(jitter)
                .predicate(recoverable)
                .build();
    }
}
