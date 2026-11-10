package com.creditagent.web.auth;

import java.util.Optional;

public interface UserRepository {

    enum CreateResult { CREATED, USER_ID_TAKEN, SSN_TAKEN }

    /**
     * Stores the user only if neither the user id nor the SSN (by its hash) is registered yet. Both
     * checks and the write happen atomically.
     */
    CreateResult create(User user);

    Optional<User> find(String userId);

    /** The user who registered the SSN with this hash (via its {@code ssn#} claim), if any. */
    Optional<User> findBySsnHash(String ssnHash);

    /**
     * Sets an existing user's report status. Never creates a user.
     *
     * @return false if no such user exists
     */
    boolean updateReportStatus(String userId, ReportStatus status);

    /**
     * Records an existing user's latest credit score. Never creates a user.
     *
     * @return false if no such user exists
     */
    boolean updateLastScore(String userId, int score, java.time.Instant at);
}
