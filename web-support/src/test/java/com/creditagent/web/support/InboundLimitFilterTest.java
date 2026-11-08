package com.creditagent.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InboundLimitFilterTest {

    final AtomicLong now = new AtomicLong();

    TokenBucket bucket(long capacity, double perSecond) {
        return new TokenBucket(capacity, perSecond, now::get);
    }

    @Test
    void theBucketAllowsABurstThenRefillsAtItsRate() {
        TokenBucket bucket = bucket(3, 2);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
        assertThat(bucket.secondsUntilNextToken()).isEqualTo(1);

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(499));
        assertThat(bucket.tryAcquire()).isFalse(); // 2 a second: a token takes 500 ms
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertThat(bucket.tryAcquire()).isTrue();

        now.addAndGet(TimeUnit.SECONDS.toNanos(60)); // a long pause never stores more than the capacity
        int taken = 0;
        while (bucket.tryAcquire()) {
            taken++;
        }
        assertThat(taken).isEqualTo(3);
    }

    @Test
    void aSlowRateGivesALongerRetryAfter() {
        TokenBucket bucket = bucket(1, 0.2); // one token every 5 s
        bucket.tryAcquire();
        assertThat(bucket.secondsUntilNextToken()).isEqualTo(5);
    }

    @Test
    void badSettingsAreRefused() {
        assertThatThrownBy(() -> new TokenBucket(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InboundLimitProperties(true, -1.0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new InboundLimitProperties(null, null, null, null))
                .isEqualTo(new InboundLimitProperties(true, 50.0, 100, 32));
    }

    @Test
    void overTheRateIs429WithRetryAfter() throws Exception {
        InboundLimitFilter filter = new InboundLimitFilter(bucket(1, 1), 0);
        assertThat(run(filter, "/api/v1/scores").getStatus()).isEqualTo(200);

        MockHttpServletResponse refused = run(filter, "/api/v1/scores");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("1");
        assertThat(refused.getContentType()).isEqualTo("application/problem+json");
        assertThat(refused.getContentAsString()).contains("\"status\":429");
    }

    @Test
    void healthChecksAreNeverLimited() throws Exception {
        InboundLimitFilter filter = new InboundLimitFilter(bucket(1, 1), 0);
        run(filter, "/api/v1/scores");
        for (int i = 0; i < 5; i++) {
            assertThat(run(filter, "/actuator/health/readiness").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void overTheConcurrencyLimitIs503AndSlotsAreReleased() throws Exception {
        InboundLimitFilter filter = new InboundLimitFilter(bucket(100, 100), 1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread slow = Thread.ofVirtual().start(() -> {
            try {
                filter.doFilter(request("/api/x"), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    await(release);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse busy = run(filter, "/api/x");
        assertThat(busy.getStatus()).isEqualTo(503);
        assertThat(busy.getHeader("Retry-After")).isEqualTo("1");

        release.countDown();
        slow.join(5_000);
        assertThat(run(filter, "/api/x").getStatus()).isEqualTo(200);
    }

    @Test
    void aFailingRequestStillReleasesItsSlot() throws Exception {
        InboundLimitFilter filter = new InboundLimitFilter(bucket(100, 100), 1);
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(request("/api/x"), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(run(filter, "/api/x").getStatus()).isEqualTo(200);
    }

    static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    static MockHttpServletResponse run(InboundLimitFilter filter, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(path), response, new MockFilterChain());
        return response;
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
