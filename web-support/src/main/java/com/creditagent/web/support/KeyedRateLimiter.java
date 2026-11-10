package com.creditagent.web.support;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Fixed one-minute windows, one per key (a client IP, a user id): at most {@code limit} requests per
 * key per window. In memory and per instance.
 *
 * <p>Memory is bounded by the number of keys active in the last minute: once more than
 * {@code maxKeys} are tracked, windows that have ended are dropped.
 */
public final class KeyedRateLimiter {

    public static final long WINDOW_MILLIS = 60_000;

    private final int limit;
    private final int maxKeys;
    private final LongSupplier millis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @param limit requests each key may make per minute
     * @param maxKeys keys tracked before ended windows are swept
     * @param millis the clock, in epoch milliseconds
     */
    public KeyedRateLimiter(int limit, int maxKeys, LongSupplier millis) {
        if (limit < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("limit and maxKeys must be at least 1");
        }
        this.limit = limit;
        this.maxKeys = maxKeys;
        this.millis = Objects.requireNonNull(millis);
    }

    /**
     * Counts one request for {@code key}.
     *
     * @return 0 if it is admitted, otherwise the whole seconds (at least 1) until the key's window
     *     ends, for a {@code Retry-After} header
     */
    public long acquire(String key) {
        long now = millis.getAsLong();
        if (windows.size() > maxKeys) {
            windows.values().removeIf(w -> now - w.start >= WINDOW_MILLIS);
        }
        Window window = windows.compute(
                key, (k, existing) -> existing == null || now - existing.start >= WINDOW_MILLIS ? new Window(now) : existing);
        if (window.count.incrementAndGet() <= limit) {
            return 0;
        }
        return Math.max(1, (WINDOW_MILLIS - (now - window.start)) / 1000);
    }

    int trackedKeys() {
        return windows.size();
    }

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }
}
