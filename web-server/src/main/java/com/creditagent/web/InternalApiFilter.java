package com.creditagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import com.creditagent.web.support.ServiceToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /internal/**}, the API other services call (not users): it requires
 * {@code Authorization: Bearer <INTERNAL_API_TOKEN>}, compared in constant time. Without a configured
 * token the internal API is off and answers 404. The path is the decoded, normalised one Spring MVC
 * routes on ({@link RequestPaths}), compared without case, so encoded or mixed-case paths cannot
 * skip the check.
 */
@Component
@Order(InternalApiFilter.ORDER)
class InternalApiFilter extends OncePerRequestFilter {

    /**
     * Right after security headers and before every limit: the per-IP limiter skips {@code /internal}
     * and the shared bucket does not, so a caller without the token must be turned away before it
     * can use up the shared bucket (and with it every user's requests and every lock check).
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 3;

    private final ServiceToken token;

    InternalApiFilter(@Value("${credit.internal.token:}") String token) {
        this.token = new ServiceToken(token);
    }

    static boolean isInternal(HttpServletRequest request) {
        return RequestPaths.path(request).toLowerCase(Locale.ROOT).startsWith("/internal/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isInternal(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!token.isConfigured()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!token.matches(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
