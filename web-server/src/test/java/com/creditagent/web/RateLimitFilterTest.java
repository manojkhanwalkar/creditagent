package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final RateLimitFilter filter = new RateLimitFilter(3, clock);

    int call(String ip, String uri) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void allowsUpToTheLimitThenReturns429() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("10.0.0.1", "/api/v1/credit-score")).isEqualTo(200);
        }
        assertThat(call("10.0.0.1", "/api/v1/credit-score")).isEqualTo(429);
    }

    @Test
    void limitsArePerClient() throws Exception {
        for (int i = 0; i < 4; i++) {
            call("10.0.0.1", "/api/v1/credit-score");
        }
        assertThat(call("10.0.0.2", "/api/v1/credit-score")).isEqualTo(200);
    }

    @Test
    void windowResetsAfterAMinute() throws Exception {
        for (int i = 0; i < 4; i++) {
            call("10.0.0.1", "/api/v1/credit-score");
        }
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertThat(call("10.0.0.1", "/api/v1/credit-score")).isEqualTo(200);
    }

    @Test
    void staticContentIsNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(call("10.0.0.1", "/index.html")).isEqualTo(200);
        }
    }
}
