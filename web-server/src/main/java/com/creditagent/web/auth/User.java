package com.creditagent.web.auth;

import java.time.Instant;

/**
 * A registered user. {@code userId} is stored lower case, so ids are unique regardless of case.
 * The SSN is kept only as a keyed hash (to check that a look-up is for the user's own SSN) and its
 * last four digits (for display).
 *
 * <p>The mobile number is collected for one purpose only: sending one-time sign-in codes by text
 * message. It is stored in its own attribute ({@code mobilePhone}); the older {@code phone} attribute,
 * collected for a password recovery that was never built, is still removed by the retention job.
 *
 * @param kycVerificationId the KYC provider's reference for the check that read the SSN from the
 *     user's card; null only for rows written before registration required a card
 * @param reportStatus whether the user has locked their credit report; {@link ReportStatus#UNLOCKED}
 *     when not set
 * @param zip the 5-digit ZIP code given at registration, for card offers; null for accounts made
 *     before it was asked for
 * @param lastScore the credit score last computed for the user (at registration, and at every score
 *     look-up), so offers can be matched at login without the SSN, which is never stored; null until
 *     one has been computed
 * @param lastScoreAt when {@code lastScore} was computed
 * @param consentVersion the version of the terms, privacy notice and FCRA authorisation the user
 *     accepted at registration ({@link RegistrationPolicy#CONSENT_VERSION}); null for accounts made
 *     before it was recorded
 * @param consentedAt when they accepted it
 * @param lastLoginAt the last successful login; with {@code createdAt}, what the retention job measures
 *     inactivity from; null until the first login after it was recorded
 * @param mobilePhone the E.164 mobile number sign-in codes can be texted to; null for accounts made
 *     before it was asked for (they get codes by email only)
 */
public record User(
        String userId,
        String passwordHash,
        String email,
        String ssnHash,
        String ssnLastFour,
        String kycVerificationId,
        Instant createdAt,
        ReportStatus reportStatus,
        String zip,
        Integer lastScore,
        Instant lastScoreAt,
        String consentVersion,
        Instant consentedAt,
        Instant lastLoginAt,
        String mobilePhone) {

    public User {
        reportStatus = reportStatus == null ? ReportStatus.UNLOCKED : reportStatus;
    }

    /** A user without a mobile number (accounts made before it was asked for). */
    public User(String userId, String passwordHash, String email, String ssnHash, String ssnLastFour,
            String kycVerificationId, Instant createdAt, ReportStatus reportStatus, String zip, Integer lastScore,
            Instant lastScoreAt, String consentVersion, Instant consentedAt, Instant lastLoginAt) {
        this(userId, passwordHash, email, ssnHash, ssnLastFour, kycVerificationId, createdAt, reportStatus,
                zip, lastScore, lastScoreAt, consentVersion, consentedAt, lastLoginAt, null);
    }

    /** A user without a ZIP code, score, consent record or login. */
    public User(String userId, String passwordHash, String email, String ssnHash, String ssnLastFour,
            String kycVerificationId, Instant createdAt, ReportStatus reportStatus) {
        this(userId, passwordHash, email, ssnHash, ssnLastFour, kycVerificationId, createdAt, reportStatus,
                null, null, null, null, null, null);
    }

    /** A user without a consent record or login. */
    public User(String userId, String passwordHash, String email, String ssnHash, String ssnLastFour,
            String kycVerificationId, Instant createdAt, ReportStatus reportStatus, String zip, Integer lastScore,
            Instant lastScoreAt) {
        this(userId, passwordHash, email, ssnHash, ssnLastFour, kycVerificationId, createdAt, reportStatus,
                zip, lastScore, lastScoreAt, null, null, null);
    }

    /** When the account was last used: the last login, or its creation if it never logged in since. */
    public Instant lastActiveAt() {
        return lastLoginAt != null && lastLoginAt.isAfter(createdAt) ? lastLoginAt : createdAt;
    }

    User withReportStatus(ReportStatus status) {
        return new User(userId, passwordHash, email, ssnHash, ssnLastFour, kycVerificationId, createdAt, status,
                zip, lastScore, lastScoreAt, consentVersion, consentedAt, lastLoginAt, mobilePhone);
    }

    User withLastScore(int score, Instant at) {
        return new User(userId, passwordHash, email, ssnHash, ssnLastFour, kycVerificationId, createdAt, reportStatus,
                zip, score, at, consentVersion, consentedAt, lastLoginAt, mobilePhone);
    }

    User withLastLogin(Instant at) {
        return new User(userId, passwordHash, email, ssnHash, ssnLastFour, kycVerificationId, createdAt, reportStatus,
                zip, lastScore, lastScoreAt, consentVersion, consentedAt, at, mobilePhone);
    }

    @Override
    public String toString() {
        return "User[userId=" + userId + ", passwordHash=<redacted>, email=<redacted>, mobilePhone=<redacted>, ssn=<redacted>]";
    }
}
