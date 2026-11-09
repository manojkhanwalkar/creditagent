package com.creditagent.web.oauth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The OAuth 2.1 authorization server that lets a Claude connector act for a signed-in user
 * ({@code credit.oauth.*}).
 *
 * @param tokenKey secret (at least 32 characters) from which the keys that sign client ids and
 *     encrypt authorization codes and access tokens are derived. Blank turns OAuth off: every OAuth
 *     endpoint answers 404.
 * @param issuer this server's public base URL, exactly as clients reach it, e.g.
 *     {@code https://credit.example.com}. It is the {@code iss} of every token and the base of every
 *     endpoint in the metadata.
 * @param resource the connector's MCP endpoint URL, the only audience tokens are issued for (RFC 8707)
 * @param allowedRedirectUris redirect URIs a client may register, matched exactly. Defaults to
 *     Claude's two connector callbacks.
 * @param allowLoopbackRedirects also accept {@code http://localhost:<port>/…} and
 *     {@code http://127.0.0.1:<port>/…} (RFC 8252), which Claude Code and other desktop clients use
 * @param accessTokenTtl how long an access token, and the grant behind it, lasts; there are no
 *     refresh tokens, so the user reconnects after this
 * @param codeTtl how long an authorization code may wait to be exchanged
 */
@ConfigurationProperties("credit.oauth")
public record OAuthProperties(
        String tokenKey,
        String issuer,
        String resource,
        List<String> allowedRedirectUris,
        Boolean allowLoopbackRedirects,
        Duration accessTokenTtl,
        Duration codeTtl) {

    public static final int MIN_KEY_LENGTH = 32;
    public static final List<String> CLAUDE_CALLBACKS =
            List.of("https://claude.ai/api/mcp/auth_callback", "https://claude.com/api/mcp/auth_callback");

    public OAuthProperties {
        if (tokenKey != null && !tokenKey.isBlank() && tokenKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("OAUTH_TOKEN_KEY must be at least " + MIN_KEY_LENGTH + " characters");
        }
        tokenKey = tokenKey == null || tokenKey.isBlank() ? null : tokenKey;
        issuer = withoutTrailingSlash(issuer == null || issuer.isBlank() ? "http://localhost:8080" : issuer);
        resource = withoutTrailingSlash(resource == null || resource.isBlank() ? "http://localhost:8088/mcp" : resource);
        allowedRedirectUris = allowedRedirectUris == null || allowedRedirectUris.isEmpty()
                ? CLAUDE_CALLBACKS
                : List.copyOf(allowedRedirectUris);
        allowLoopbackRedirects = allowLoopbackRedirects == null || allowLoopbackRedirects;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(1) : accessTokenTtl;
        codeTtl = codeTtl == null ? Duration.ofSeconds(60) : codeTtl;
        if (!accessTokenTtl.isPositive() || !codeTtl.isPositive()) {
            throw new IllegalArgumentException("credit.oauth: access-token-ttl and code-ttl must be positive");
        }
    }

    public boolean enabled() {
        return tokenKey != null;
    }

    static String withoutTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public String toString() {
        return "OAuthProperties[tokenKey=<redacted>, issuer=" + issuer + ", resource=" + resource
                + ", allowedRedirectUris=" + allowedRedirectUris + ", allowLoopbackRedirects=" + allowLoopbackRedirects
                + ", accessTokenTtl=" + accessTokenTtl + ", codeTtl=" + codeTtl + "]";
    }
}
