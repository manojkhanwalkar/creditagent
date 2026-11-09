package com.creditagent.clients;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.nio.channels.UnresolvedAddressException;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.core.retry.RetryTemplate;

/**
 * Runs a call again when it fails in a way that is likely to pass on a later try. What counts as
 * recoverable depends on whether repeating the request is safe:
 *
 * <ul>
 *   <li>{@link #transientFailure} for idempotent calls (reads, pure computations): any I/O failure,
 *       including a timeout or a connection reset after the request was sent.
 *   <li>{@link #notDelivered} for calls that must not run twice: only failures that prove the
 *       request never reached the server (connection refused, connect timeout, unknown host).
 * </ul>
 *
 * <p>Retry at one layer only. If a service already retries its own downstream call, its callers
 * should not also retry on the error it returns, or attempts multiply along the chain.
 */
public final class Retries {

    /** Statuses that say "not now" rather than "no": overload, a restart, a gateway that lost its upstream. */
    public static final Set<Integer> RECOVERABLE_STATUSES = Set.of(429, 502, 503, 504);
    /** Statuses a server sends before it starts the work, so even non-idempotent calls may retry them. */
    public static final Set<Integer> REFUSED_STATUSES = Set.of(429, 503);

    private Retries() {}

    /**
     * Calls {@code call}, retrying per {@code settings} while {@code recoverable} accepts the failure.
     * Once retries run out, or on a failure that is not recoverable, the last failure is thrown as is.
     */
    public static <T> T call(RetrySettings settings, Predicate<Throwable> recoverable, Supplier<T> call) {
        if (settings.maxAttempts() == 1) {
            return call.get();
        }
        return new RetryTemplate(settings.policy(recoverable)).invoke(call);
    }

    /** Any I/O failure, wherever it sits in the cause chain. For idempotent calls. */
    public static boolean transientFailure(Throwable t) {
        return find(t, IOException.class) != null || find(t, UncheckedIOException.class) != null
                || find(t, RetryableStatusException.class) != null;
    }

    /** A failure that proves the request was never delivered, or a refusal before any work started. */
    public static boolean notDelivered(Throwable t) {
        RetryableStatusException status = find(t, RetryableStatusException.class);
        if (status != null) {
            return REFUSED_STATUSES.contains(status.status());
        }
        return find(t, ConnectException.class) != null
                || find(t, HttpConnectTimeoutException.class) != null
                || find(t, UnknownHostException.class) != null
                || find(t, NoRouteToHostException.class) != null
                || find(t, UnresolvedAddressException.class) != null;
    }

    /**
     * The wait a {@code Retry-After} header asks for, in delta-seconds form; null when absent or in
     * the HTTP-date form (rare between services, and treated as no hint).
     */
    public static Duration retryAfter(String header) {
        if (header == null || !header.strip().matches("\\d{1,9}")) {
            return null;
        }
        return Duration.ofSeconds(Long.parseLong(header.strip()));
    }

    /**
     * Sleeps for {@code wait}. @return false if interrupted (the interrupt flag is restored), so the
     * caller stops retrying.
     */
    public static boolean pause(Duration wait) {
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static <E extends Throwable> E find(Throwable t, Class<E> type) {
        int depth = 0;
        for (Throwable cause = t; cause != null && depth < 16; cause = cause.getCause(), depth++) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    /** A response whose status is worth retrying; carries the status so callers can classify it. */
    public static final class RetryableStatusException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;

        public RetryableStatusException(int status) {
            super("Recoverable HTTP status " + status, null, false, false);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
