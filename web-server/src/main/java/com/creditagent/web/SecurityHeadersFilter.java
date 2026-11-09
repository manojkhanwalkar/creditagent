package com.creditagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Baseline browser hardening. The UI has no inline script or style, so the CSP can be strict.
 * When authentication is added, replace this with Spring Security's header support.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = String.join(
            "; ",
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "font-src 'self'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        if (RequestPaths.isApi(request) || RequestPaths.isOAuth(request)) {
            // Scores are personal data, and OAuth answers carry codes and tokens: never cache them.
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
