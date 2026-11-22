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
     * @param timeout longest a DynamoDB call may take, retries included; each attempt gets half of
     *     it. Defaults to 5 s. Without it the SDK can wait for minutes on a hung connection, holding
     *     a request thread the whole time.
     */
    public record DynamoDb(
            String endpoint,
            @NotBlank String region,
            @NotBlank String usersTable,
            @NotBlank String sessionsTable,
            boolean createTables,
            Duration timeout) {

        public DynamoDb {
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }

        /** DynamoDB hosts that mean "local development": this machine, or the compose service. */
        static final java.util.Set<String> LOCAL_HOSTS =
                java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1", "dynamodb", "dynamodb-local");

        /**
         * @return true if the endpoint is a DynamoDB on this machine or in compose; false for AWS (an AWS,
         *     VPC or FIPS endpoint override included). Guards the development-only stand-ins.
         */
        public boolean local() {
            if (endpoint == null || endpoint.isBlank()) {
                return false;
            }
            try {
                String host = java.net.URI.create(endpoint.strip()).getHost();
                return host != null && LOCAL_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=<redacted>, ssnHmacKey=<redacted>, jwtIssuer=" + jwtIssuer + ", tokenTtl=" + tokenTtl
                + ", sessionIdleTimeout=" + sessionIdleTimeout + ", dynamodb=" + dynamodb + "]";
    }
}
