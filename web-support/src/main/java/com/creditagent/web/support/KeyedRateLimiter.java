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
 * <p>Memory is bounded by {@code maxKeys}: past it, windows that have ended are swept (at most once
 * a second, so a flood of new keys does not cost a full scan per request), and while the map is
 * still full of live windows a key it does not track yet is refused. Under a flood from many
 * addresses, clients already tracked keep their own limit and new ones wait a second.
 */
public final class KeyedRateLimiter {

    public static final long WINDOW_MILLIS = 60_000;

    private final int limit;
    private final int maxKeys;
    private final LongSupplier millis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    /** When ended windows were last swept; sweeps run at most once per {@link #SWEEP_INTERVAL_MILLIS}. */
    private volatile long lastSweep = Long.MIN_VALUE / 2; // far past, without overflowing now - lastSweep

    static final long SWEEP_INTERVAL_MILLIS = 1_000;

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
        if (windows.size() >= maxKeys) {
            if (now - lastSweep >= SWEEP_INTERVAL_MILLIS) {
                lastSweep = now;
                windows.values().removeIf(w -> now - w.start >= WINDOW_MILLIS);
            }
            if (windows.size() >= maxKeys && !windows.containsKey(key)) {
                return 1; // full of live windows: no room for a new client right now
            }
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
