package com.creditagent.web.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * A logged-in session. The JWT carries {@code sessionId} as its {@code jti}; a token is only
 * accepted while its session exists and has been used within the idle timeout.
 *
 * @param expiresAt {@code lastActivityAt} plus the idle timeout; also DynamoDB's TTL attribute
 */
public record Session(String sessionId, String userId, Instant createdAt, Instant lastActivityAt, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Session touchedAt(Instant now, Duration idleTimeout) {
        return new Session(sessionId, userId, createdAt, now, now.plus(idleTimeout));
    }
}
