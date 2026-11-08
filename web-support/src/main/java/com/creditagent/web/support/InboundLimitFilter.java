package com.creditagent.web.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Protects a service from more work than it can take, before any of that work starts:
 *
 * <ul>
 *   <li><b>Rate:</b> a {@link TokenBucket}. Over the rate (after the burst allowance) a request gets
 *       {@code 429 Too Many Requests} with {@code Retry-After}.
 *   <li><b>Concurrency:</b> at most {@code maxConcurrent} requests in progress at once (a bulkhead).
 *       Over it, a request gets {@code 503 Service Unavailable} with {@code Retry-After: 1}
 *       immediately rather than queueing on a thread.
 * </ul>
 *
 * <p>Both answers are ones the services' HTTP clients treat as recoverable, so a caller backs off
 * and retries instead of failing. Actuator endpoints are never limited, so health checks keep
 * working under load.
 */
public class InboundLimitFilter extends OncePerRequestFilter {

    static final String BODY_429 =
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests. Please retry shortly.\"}";
    static final String BODY_503 =
            "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"The service is busy. Please retry shortly.\"}";

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setAlwaysUseFullPath(true);
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private final TokenBucket bucket;
    private final Semaphore inFlight;

    /** @param maxConcurrent at most this many requests in progress; 0 for no limit */
    public InboundLimitFilter(TokenBucket bucket, int maxConcurrent) {
        this.bucket = bucket;
        this.inFlight = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The decoded path without ;params, as Spring MVC sees it. A raw getRequestURI() prefix check
        // would let /actuator/%2e%2e/api/... skip the limits; decoding alone still leaves
        // /actuator/../api/..., so any path with a dot-dot segment is limited like any other.
        String path = PATHS.getLookupPathForRequest(request);
        return path.startsWith("/actuator/") && !path.contains("..");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!bucket.tryAcquire()) {
            reject(response, 429, bucket.secondsUntilNextToken(), BODY_429);
            return;
        }
        if (inFlight == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!inFlight.tryAcquire()) {
            reject(response, 503, 1, BODY_503);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            inFlight.release();
        }
    }

    private static void reject(HttpServletResponse response, int status, long retryAfterSeconds, String body)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/problem+json");
        response.getWriter().write(body);
    }
}
