package com.creditagent.web.auth;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository {

    void save(Session session);

    Optional<Session> find(String sessionId);

    /**
     * Records activity on a session that still exists. Never re-creates one: a request that was in
     * flight while the session was logged out or swept must not bring it back.
     *
     * @return false if the session no longer exists
     */
    boolean touch(String sessionId, Instant lastActivityAt, Instant expiresAt);

    void delete(String sessionId);

    /** Removes every session whose {@code expiresAt} is not after {@code now}. @return how many */
    int deleteExpired(Instant now);
}
