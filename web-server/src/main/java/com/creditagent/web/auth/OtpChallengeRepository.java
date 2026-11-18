package com.creditagent.web.auth;

import com.creditagent.web.auth.otp.OtpChannel;
import java.time.Instant;
import java.util.Optional;

/**
 * Login challenges between the password and code steps. Every change that guards a limit is a
 * conditional write, so concurrent requests cannot exceed the attempt or send limits.
 */
public interface OtpChallengeRepository {

    void create(OtpChallenge challenge);

    /** The challenge, expired or not; the caller checks expiry. */
    Optional<OtpChallenge> find(String challengeId);

    /**
     * Replaces the current code, only if the challenge exists, has not expired at {@code now}, has
     * sent fewer than {@code maxSends} codes, and sent none after {@code notSentSince}.
     *
     * @return false if any condition failed (nothing was changed)
     */
    boolean recordSend(String challengeId, OtpChannel channel, String codeHash, Instant now, Instant codeExpiresAt,
            Instant notSentSince, int maxSends);

    /**
     * Counts one code attempt, only while fewer than {@code maxAttempts} were made.
     *
     * @return false if the challenge is gone or out of attempts
     */
    boolean recordAttempt(String challengeId, int maxAttempts);

    /**
     * Deletes the challenge only if its current code hash is still {@code codeHash}, so one code opens
     * at most one session and a code replaced by a resend in the meantime opens none.
     *
     * @return true if this call used it up
     */
    boolean consume(String challengeId, String codeHash);

    void delete(String challengeId);

    /**
     * Counts one code sent to {@code userId} in the clock hour of {@code now}, only while fewer than
     * {@code maxPerHour} were.
     *
     * @return false if the account's hourly limit is reached
     */
    boolean reserveUserSend(String userId, Instant now, int maxPerHour);

    /**
     * Undoes {@link #recordSend} for a code that was not delivered: removes the code and its send time
     * and gives back one send, only while {@code codeHash} is still the current code.
     */
    void releaseSend(String challengeId, String codeHash);

    /** Undoes one {@link #reserveUserSend} in the clock hour of {@code now}; never below zero. */
    void releaseUserSend(String userId, Instant now);
}
