package com.creditagent.web.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    enum CreateResult { CREATED, USER_ID_TAKEN, SSN_TAKEN, EMAIL_TAKEN }

    /**
     * One account as the retention job sees it.
     *
     * @param hasLegacyPhone the row still holds a phone number from before it stopped being collected
     */
    record AccountSummary(String userId, String email, Instant lastActiveAt, boolean hasLegacyPhone) {
        @Override
        public String toString() {
            return "AccountSummary[userId=" + userId + ", email=<redacted>, lastActiveAt=" + lastActiveAt + "]";
        }
    }

    /** A page of accounts and the cursor for the next one (null when this was the last page). */
    record Page(List<AccountSummary> accounts, String next) {}

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
    boolean updateLastScore(String userId, int score, Instant at);

    /** Records a successful login. Never creates a user. @return false if no such user exists */
    boolean recordLogin(String userId, Instant at);

    /**
     * Deletes the user and the claims on their SSN and email, atomically for the user and SSN claim. A
     * claim is only removed while it still names this user. Idempotent.
     *
     * @return false if there was no such user
     */
    boolean delete(User user);

    /**
     * Accounts in key order after {@code after} (null = from the start), at most {@code limit}; claim
     * items are skipped.
     */
    Page accounts(String after, int limit);

    /** Removes a phone number stored before it stopped being collected. Never creates a user. */
    void removeLegacyPhone(String userId);
}
