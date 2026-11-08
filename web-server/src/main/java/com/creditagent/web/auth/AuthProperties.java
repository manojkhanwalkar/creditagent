package com.creditagent.web.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwtSecret HMAC-SHA256 key for signing JWTs (at least 32 characters)
 * @param ssnHmacKey key for the keyed hash of each user's SSN (at least 32 characters)
 * @param jwtIssuer {@code iss} claim written and required on every token
 * @param tokenTtl hard cap on a token's life
 * @param sessionIdleTimeout a session with no authenticated request for this long is ended
 * @param dynamodb where users and sessions are stored
 */
@Validated
@ConfigurationProperties("credit.auth")
public record AuthProperties(
        @NotBlank @Size(min = 32) String jwtSecret,
        @NotBlank @Size(min = 32) String ssnHmacKey,
        @NotBlank String jwtIssuer,
        @NotNull Duration tokenTtl,
        @NotNull Duration sessionIdleTimeout,
        @Valid @NotNull DynamoDb dynamodb) {

    /**
     * @param endpoint override, e.g. {@code http://localhost:8000} for DynamoDB Local; blank = AWS
     * @param createTables create missing tables at start-up (local and test only)
     */
    public record DynamoDb(
            String endpoint,
            @NotBlank String region,
            @NotBlank String usersTable,
            @NotBlank String sessionsTable,
            boolean createTables) {}

    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=<redacted>, ssnHmacKey=<redacted>, jwtIssuer=" + jwtIssuer + ", tokenTtl=" + tokenTtl
                + ", sessionIdleTimeout=" + sessionIdleTimeout + ", dynamodb=" + dynamodb + "]";
    }
}
