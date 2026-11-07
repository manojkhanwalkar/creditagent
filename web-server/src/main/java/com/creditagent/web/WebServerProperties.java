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
 * @param timeout connect/read timeout for both downstream calls
 * @param rateLimitPerMinute max score look-ups per client IP per minute (SSN enumeration guard)
 */
@Validated
@ConfigurationProperties("credit.web")
public record WebServerProperties(
        @NotBlank String mcpUrl,
        @NotBlank String scoreCalculatorUrl,
        @NotNull Duration timeout,
        @Min(1) int rateLimitPerMinute) {}
