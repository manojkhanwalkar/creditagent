package com.creditagent.web.auth;

import com.creditagent.web.auth.otp.OtpChannel;
import java.time.Instant;

/**
 * A login between its two steps: the password was right, the one-time code is still to come.
 *
 * @param challengeId the random id the browser holds between the steps (stored only as a hash)
 * @param expiresAt when the user must start again from the password
 * @param channel where the current code was sent; null until one is
 * @param codeHash keyed hash of the current code, bound to this challenge; null until one is sent
 * @param codeExpiresAt when the current code stops working; null until one is sent
 * @param lastSentAt when the current code was sent; null until one is
 * @param sends codes sent so far
 * @param attempts codes tried so far
 */
public record OtpChallenge(
        String challengeId,
        String userId,
        Instant createdAt,
        Instant expiresAt,
        OtpChannel channel,
        String codeHash,
        Instant codeExpiresAt,
        Instant lastSentAt,
        int sends,
        int attempts) {

    /** A challenge just opened by the password step: no code sent or tried yet. */
    public static OtpChallenge opened(String challengeId, String userId, Instant now, Instant expiresAt) {
        return new OtpChallenge(challengeId, userId, now, expiresAt, null, null, null, null, 0, 0);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "OtpChallenge[userId=" + userId + ", channel=" + channel + ", sends=" + sends + ", attempts=" + attempts + "]";
    }
}
