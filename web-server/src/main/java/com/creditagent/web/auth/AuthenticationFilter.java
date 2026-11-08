package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requires {@code Authorization: Bearer <jwt>} on every {@code /api/} call except register and
 * login. Runs after the rate limiter, so login attempts are rate limited too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AuthenticationFilter extends OncePerRequestFilter {

    static final Set<String> OPEN_PATHS = Set.of(AuthController.BASE + "/register", AuthController.BASE + "/login");
    private static final String BEARER = "Bearer ";

    private final AuthService auth;
    private final JsonMapper json;

    AuthenticationFilter(AuthService auth, JsonMapper json) {
        this.auth = auth;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || OPEN_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Always authenticate: a missing or non-Bearer header yields an empty token, which
        // AuthService refuses. No request reaches the chain without passing authenticate().
        AuthenticatedUser user;
        try {
            user = auth.authenticate(bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)));
        } catch (UnauthenticatedException e) {
            reject(response, e.getMessage());
            return;
        }
        if (user == null) {
            // Fail closed: never let a request through without a resolved user.
            reject(response, "Log in to continue.");
            return;
        }
        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return "";
        }
        return header.substring(BEARER.length()).strip();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
