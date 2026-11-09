package com.creditagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
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

    /** After the per-client and shared limits and the body-size cap; before user authentication. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;
    static final int MIN_TOKEN_LENGTH = 32;
    private static final String BEARER = "Bearer ";

    private final byte[] token;

    InternalApiFilter(@Value("${credit.internal.token:}") String token) {
        if (token != null && !token.isBlank() && token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("INTERNAL_API_TOKEN must be at least " + MIN_TOKEN_LENGTH + " characters");
        }
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
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
        if (token == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] presented = header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? header.substring(BEARER.length()).strip().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(token, presented)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
