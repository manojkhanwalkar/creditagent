package com.creditagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window, per-IP limit on score look-ups. With no authentication yet, this is the only thing
 * slowing down someone trying to enumerate SSNs against the API.
 *
 * <p>In-memory and per-instance: good enough for a single node. Behind a load balancer, move this
 * to the gateway (and configure {@code server.forward-headers-strategy} so the client IP is real).
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final int limit;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    RateLimitFilter(WebServerProperties properties) {
        this(properties.rateLimitPerMinute(), Clock.systemUTC());
    }

    RateLimitFilter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.values().removeIf(w -> now - w.start >= WINDOW_MILLIS);
        }
        Window window = windows.compute(
                request.getRemoteAddr(),
                (ip, existing) -> existing == null || now - existing.start >= WINDOW_MILLIS ? new Window(now) : existing);
        if (window.count.incrementAndGet() > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (WINDOW_MILLIS - (now - window.start)) / 1000)));
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }
}
