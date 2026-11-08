package com.creditagent.web.auth;

import java.time.Instant;

/**
 * A registered user. {@code userId} is stored lower case, so ids are unique regardless of case.
 * The SSN is kept only as a keyed hash (to check that a look-up is for the user's own SSN) and its
 * last four digits (for display). Email and phone are kept for password recovery, not built yet.
 *
 * @param kycVerificationId the KYC provider's reference for the check that read the SSN from the
 *     user's card; null only for rows written before registration required a card
 */
public record User(
        String userId,
        String passwordHash,
        String email,
        String phone,
        String ssnHash,
        String ssnLastFour,
        String kycVerificationId,
        Instant createdAt) {

    @Override
    public String toString() {
        return "User[userId=" + userId + ", passwordHash=<redacted>, email=<redacted>, phone=<redacted>, ssn=<redacted>]";
    }
}
