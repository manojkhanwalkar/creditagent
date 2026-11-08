package com.creditagent.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param url Normalized Report Generator base URL, e.g. {@code http://localhost:8084}
 * @param timeout connect/read timeout
 */
@Validated
@ConfigurationProperties("credit.nrg")
public record NrgProperties(@NotBlank String url, @NotNull Duration timeout) {}
