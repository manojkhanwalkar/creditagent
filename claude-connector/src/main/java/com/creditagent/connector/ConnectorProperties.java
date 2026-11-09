package com.creditagent.connector;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Claude connector ({@code credit.connector.*}).
 *
 * @param publicUrl this connector's public base URL, as Claude reaches it, e.g.
 *     {@code https://connector.example.com}. The MCP endpoint is {@code <publicUrl>/mcp}, and that is
 *     the resource every access token must be issued for.
 * @param authorizationServer the web server's public URL: the OAuth issuer Claude signs users in with
 * @param webServerUrl the web server's internal URL, for token introspection and the report lock
 * @param serviceToken the internal API token ({@code INTERNAL_API_TOKEN}) for the web server and the
 *     Credit Report MCP server
 * @param mcpUrl the Credit Report MCP server
 * @param scoreCalculatorUrl the Score Calculator
 * @param clientAgentUrl the client agent, which asks the Credit Intelligence Agent for advice over A2A
 * @param timeout per-try timeout for the web server and the Score Calculator
 * @param mcpTimeout longest wait for one report read, the MCP server's own NRG retries included
 * @param adviceTimeout longest wait for advice; longer than the client agent's own 90 s, so this
 *     side never gives up on an answer that is still coming
 * @param allowedOrigins browser origins allowed to call {@code /mcp}; requests without an Origin
 *     header (server to server, as Claude's are) are always allowed. Guards against DNS rebinding.
 * @param requestsPerUserPerMinute how many MCP requests one user may make a minute
 */
@Validated
@ConfigurationProperties("credit.connector")
public record ConnectorProperties(
        @NotBlank String publicUrl,
        @NotBlank String authorizationServer,
        @NotBlank String webServerUrl,
        String serviceToken,
        @NotBlank String mcpUrl,
        @NotBlank String scoreCalculatorUrl,
        @NotBlank String clientAgentUrl,
        Duration timeout,
        Duration mcpTimeout,
        Duration adviceTimeout,
        List<String> allowedOrigins,
        @Min(1) Integer requestsPerUserPerMinute) {

    public ConnectorProperties {
        publicUrl = withoutTrailingSlash(publicUrl);
        authorizationServer = withoutTrailingSlash(authorizationServer);
        serviceToken = serviceToken == null || serviceToken.isBlank() ? null : serviceToken;
        timeout = timeout == null ? Duration.ofSeconds(12) : timeout;
        mcpTimeout = mcpTimeout == null ? Duration.ofSeconds(20) : mcpTimeout;
        adviceTimeout = adviceTimeout == null ? Duration.ofSeconds(95) : adviceTimeout;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        requestsPerUserPerMinute = requestsPerUserPerMinute == null ? 60 : requestsPerUserPerMinute;
    }

    /** The MCP endpoint's URL: the OAuth resource (RFC 8707) this connector serves. */
    public String resource() {
        return publicUrl + McpHttpController.MCP_PATH;
    }

    /** RFC 9728 metadata URL, sent in every {@code WWW-Authenticate} challenge. */
    public String resourceMetadataUrl() {
        return publicUrl + McpHttpController.RESOURCE_METADATA_PATH;
    }

    private static String withoutTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public String toString() {
        return "ConnectorProperties[publicUrl=" + publicUrl + ", authorizationServer=" + authorizationServer
                + ", webServerUrl=" + webServerUrl + ", serviceToken=<redacted>, mcpUrl=" + mcpUrl + "]";
    }
}
