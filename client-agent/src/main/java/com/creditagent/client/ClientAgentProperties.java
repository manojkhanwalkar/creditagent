package com.creditagent.client;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param intelligenceAgentUrl base URL of the Credit Intelligence Agent (its agent card is fetched from here) */
@Validated
@ConfigurationProperties("credit.client")
public record ClientAgentProperties(@NotBlank String intelligenceAgentUrl) {}
