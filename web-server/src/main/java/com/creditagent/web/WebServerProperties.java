package com.creditagent.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param mcpUrl Credit Report MCP server endpoint, e.g. {@code http://localhost:8082/mcp}
 * @param scoreCalculatorUrl Score Calculator base URL, e.g. {@code http://localhost:8081}
 * @param timeout connect/read timeout for the Score Calculator
 * @param mcpTimeout longest wait for one MCP look-up, reconnects and retries included. Longer than {@code timeout} on purpose: one read
 *     includes the MCP server's own retries of NRG (up to 3 × 5 s plus the waits, about 17.4 s), and giving up
 *     sooner would abandon reads the server is still completing. Defaults to 20 s.
 * @param rateLimitPerMinute max score look-ups per client IP per minute (SSN enumeration guard)
 */
@Validated
@ConfigurationProperties("credit.web")
public record WebServerProperties(
        @NotBlank String mcpUrl,
        @NotBlank String scoreCalculatorUrl,
        @NotNull Duration timeout,
        @Min(1) int rateLimitPerMinute,
        Duration mcpTimeout) {

    public WebServerProperties {
        mcpTimeout = mcpTimeout == null ? DEFAULT_MCP_TIMEOUT : mcpTimeout;
    }

    public static final Duration DEFAULT_MCP_TIMEOUT = Duration.ofSeconds(20);
}
