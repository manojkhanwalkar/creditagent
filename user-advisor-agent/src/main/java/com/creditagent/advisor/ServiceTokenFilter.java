package com.creditagent.advisor;

import com.creditagent.web.support.ServiceToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Only the web server may talk to this agent: everything but actuator health needs
 * {@code Authorization: Bearer <INTERNAL_API_TOKEN>}. Without a configured token the API is off (404).
 * The path is the decoded one Spring MVC routes on, so encoded paths cannot skip the check.
 */
class ServiceTokenFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setAlwaysUseFullPath(true);
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private final ServiceToken token;

    ServiceTokenFilter(ServiceToken token) {
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
}
