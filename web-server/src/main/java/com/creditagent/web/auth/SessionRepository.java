package com.creditagent.web.auth;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository {

    void save(Session session);

    Optional<Session> find(String sessionId);

    void delete(String sessionId);

    /** Removes every session whose {@code expiresAt} is not after {@code now}. @return how many */
    int deleteExpired(Instant now);
}
