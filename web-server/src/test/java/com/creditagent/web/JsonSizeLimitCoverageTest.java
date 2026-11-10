package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint that reads an untrusted JSON or form body is under the request-size limit. The
 * filter is registered by URL pattern, which a {@code @WebMvcTest} does not apply, so a mistyped
 * pattern would otherwise go unnoticed. Patterns are matched as the servlet container does: exact,
 * or a {@code /prefix/*} that covers the prefix and everything below it.
 */
class JsonSizeLimitCoverageTest {

    static final List<String> BODY_ENDPOINTS = List.of(
            "/api/v1/credit-score",
            "/api/v1/chat",
            "/api/v1/auth/login",
            "/api/v1/credit-report/status",
            "/internal/v1/report-locks/lookup",
            "/internal/v1/connector/introspect",
            "/internal/v1/connector/report-status",
            "/oauth/register",
            "/oauth/authorize",
            "/oauth/token",
            "/oauth/revoke");

    @Test
    void everyBodyEndpointIsCapped() {
        Collection<String> patterns = new WebServerConfiguration().jsonRequestSizeLimitFilter().getUrlPatterns();
        for (String path : BODY_ENDPOINTS) {
            assertThat(patterns).as(path).anyMatch(pattern -> matches(pattern, path));
        }
    }

    @Test
    void theMatcherFollowsServletRules() {
        assertThat(matches("/oauth/token", "/oauth/token")).isTrue();
        assertThat(matches("/oauth/token", "/oauth/tokens")).isFalse();
        assertThat(matches("/internal/v1/connector/*", "/internal/v1/connector/introspect")).isTrue();
        assertThat(matches("/internal/v1/connector/*", "/internal/v1/connectors")).isFalse();
    }

    static boolean matches(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }
}
