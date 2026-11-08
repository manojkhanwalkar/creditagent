package com.creditagent.web.support;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Token bucket: holds up to {@code capacity} tokens and gains {@code refillPerSecond} tokens a
 * second. Each request takes one token; with none left the request is refused. It allows short
 * bursts up to {@code capacity} while holding the long-run rate to {@code refillPerSecond}.
 *
 * <p>Refill is computed lazily from elapsed time on each call, so there is no timer thread.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoTime;
    private double tokens;
    private long lastRefill;

    public TokenBucket(long capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucket(long capacity, double refillPerSecond, LongSupplier nanoTime) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be at least 1 and refillPerSecond positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / TimeUnit.SECONDS.toNanos(1);
        this.nanoTime = nanoTime;
        this.tokens = capacity;
        this.lastRefill = nanoTime.getAsLong();
    }

    /** Takes a token if one is available. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** Whole seconds until the next token, at least 1; for a {@code Retry-After} header. */
    public synchronized long secondsUntilNextToken() {
        refill();
        double missing = Math.max(0, 1 - tokens);
        long nanos = (long) Math.ceil(missing / refillPerNano);
        return Math.max(1, (long) Math.ceil(nanos / 1e9));
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefill = now;
        }
    }
}
