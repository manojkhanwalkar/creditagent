package com.creditagent.web.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * A logged-in session. The JWT carries {@code sessionId} as its {@code jti}; a token is only
 * accepted while its session exists and has been used within the idle timeout. The user's SSN hash
 * is copied in at login, so checking a look-up needs no read of the users table.
 *
 * @param expiresAt {@code lastActivityAt} plus the idle timeout; also DynamoDB's TTL attribute
 */
public record Session(
        String sessionId,
        String userId,
        String ssnHash,
        String ssnLastFour,
        Instant createdAt,
        Instant lastActivityAt,
        Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Session touchedAt(Instant now, Duration idleTimeout) {
        return new Session(sessionId, userId, ssnHash, ssnLastFour, createdAt, now, now.plus(idleTimeout));
    }

    @Override
    public String toString() {
        return "Session[sessionId=<redacted>, userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
