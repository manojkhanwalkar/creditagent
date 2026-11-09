package com.creditagent.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class KeyedRateLimiterTest {

    final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    void admitsUpToTheLimitPerKeyThenSaysHowLongToWait() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(2, 100, now::get);
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("b")).isZero();
        now.addAndGet(15_000);
        assertThat(limiter.acquire("a")).isEqualTo(45);
    }

    @Test
    void aNewWindowStartsAfterAMinute() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(1, 100, now::get);
        limiter.acquire("a");
        assertThat(limiter.acquire("a")).isPositive();
        now.addAndGet(KeyedRateLimiter.WINDOW_MILLIS);
        assertThat(limiter.acquire("a")).isZero();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(1, 100, now::get);
        limiter.acquire("a");
        now.addAndGet(KeyedRateLimiter.WINDOW_MILLIS - 1);
        assertThat(limiter.acquire("a")).isEqualTo(1);
    }

    @Test
    void endedWindowsAreSweptOnceTooManyKeysAreTracked() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(5, 2, now::get);
        limiter.acquire("a");
        limiter.acquire("b");
        limiter.acquire("c");
        now.addAndGet(KeyedRateLimiter.WINDOW_MILLIS);
        limiter.acquire("d");
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void rejectsNonsenseLimits() {
        assertThatThrownBy(() -> new KeyedRateLimiter(0, 1, now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyedRateLimiter(1, 0, now::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
