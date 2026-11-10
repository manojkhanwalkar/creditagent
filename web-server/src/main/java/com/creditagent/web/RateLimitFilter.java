package com.creditagent.web;

import com.creditagent.web.support.KeyedRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window, per-IP limit on every API call, including login and registration, and on the
 * OAuth sign-in form. It runs before authentication, so it also slows password guessing and SSN
 * enumeration by a logged-in user.
 *
 * <p>The other OAuth endpoints (token, registration, revocation) are not limited per IP: they are
 * called by the Claude servers, which share a few addresses among every user. A guessed
 * authorization code or token is worthless anyway (each is encrypted and bound to a PKCE verifier).
 *
 * <p>In-memory and per-instance: good enough for a single node. Behind a load balancer, move this
 * to the gateway (and configure {@code server.forward-headers-strategy} so the client IP is real).
 */
@Component
@Order(RateLimitFilter.ORDER)
class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Before the shared inbound limits ({@code WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER}): a
     * client over its own limit is stopped here, so it cannot use up the shared token bucket and
     * get every other user refused.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 4;

    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final KeyedRateLimiter limiter;

    @Autowired
    RateLimitFilter(WebServerProperties properties) {
        this(properties.rateLimitPerMinute(), Clock.systemUTC());
    }

    RateLimitFilter(int limit, Clock clock) {
        this.limiter = new KeyedRateLimiter(limit, MAX_TRACKED_CLIENTS, clock::millis);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isApi(request) && !RequestPaths.isOAuthSignIn(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long wait = limiter.acquire(request.getRemoteAddr());
        if (wait > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(wait));
            return;
        }
        chain.doFilter(request, response);
    }
}
