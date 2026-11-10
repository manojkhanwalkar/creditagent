package com.creditagent.web.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * For internal-only services (the User Advisor Agent, the Offers service): everything but actuator
 * health needs {@code Authorization: Bearer <INTERNAL_API_TOKEN>}. Without a configured token the API
 * is off (404). The path is the decoded one Spring MVC routes on, so encoded paths cannot skip the
 * check. Register it before the inbound limits ({@link #registration}), so callers without the token
 * cannot use up the shared bucket.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setAlwaysUseFullPath(true);
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private final ServiceToken token;

    public ServiceTokenFilter(ServiceToken token) {
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getLookupPathForRequest(request);
        return path.startsWith("/actuator/") && !path.contains("..");
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

    /** The filter on every path, ordered just before the inbound limits. */
    public static org.springframework.boot.web.servlet.FilterRegistrationBean<ServiceTokenFilter> registration(String token) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<ServiceTokenFilter> registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new ServiceTokenFilter(new ServiceToken(token)));
        registration.addUrlPatterns("/*");
        registration.setOrder(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER - 1);
        return registration;
    }
}
