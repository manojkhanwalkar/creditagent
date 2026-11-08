package com.creditagent.web.kyc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param url KYC provider base URL, e.g. {@code http://localhost:8087}
 * @param timeout connect/read timeout; image checks are slower than the other downstream calls
 */
@Validated
@ConfigurationProperties("credit.kyc")
public record KycClientProperties(@NotBlank String url, @NotNull Duration timeout) {}
