package com.creditagent.clients;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Retries a {@code RestClient} request on recoverable failures, with the backoff and jitter of
 * {@link RetrySettings}. An I/O failure or a recoverable status ({@link Retries#RECOVERABLE_STATUSES})
 * is retried; on the last try the response is returned as it came, so the client's normal error
 * handling sees the final 503 or 429.
 *
 * <p>For a call that must not run twice, construct with {@code idempotent = false}: then only
 * failures that prove the request never arrived, and 429 or 503, are retried.
 */
public class RetryingInterceptor implements ClientHttpRequestInterceptor {

    private final RetrySettings settings;
    private final Predicate<Throwable> recoverable;
    private final Set<Integer> retryStatuses;

    public RetryingInterceptor(RetrySettings settings, boolean idempotent) {
        this.settings = Objects.requireNonNull(settings);
        this.recoverable = idempotent ? Retries::transientFailure : Retries::notDelivered;
        this.retryStatuses = idempotent ? Retries.RECOVERABLE_STATUSES : Retries.REFUSED_STATUSES;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        AtomicInteger attempt = new AtomicInteger();
        try {
            return Retries.call(settings, recoverable, () -> {
                ClientHttpResponse response;
                int status;
                try {
                    response = execution.execute(request, body);
                    status = response.getStatusCode().value();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (attempt.incrementAndGet() < settings.maxAttempts() && retryStatuses.contains(status)) {
                    response.close();
                    throw new Retries.RetryableStatusException(status);
                }
                return response;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
