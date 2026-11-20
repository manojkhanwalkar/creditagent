package com.creditagent.web.auth;

import com.creditagent.common.AuditLog;
import com.creditagent.web.auth.AuthExceptions.InvalidOtpRequestException;
import com.creditagent.web.auth.AuthExceptions.OtpFailedException;
import com.creditagent.web.auth.AuthExceptions.TooManyCodesException;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpDeliveryException;
import com.creditagent.web.auth.otp.OtpSender;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-step login: the password, then a six-digit code sent by email or text message, whichever the
 * user picks. Only {@link #verify} opens a session.
 *
 * <ol>
 *   <li>{@link #start}: the password is checked and a challenge opened. The answer lists where a code
 *       can go, masked. No code is sent yet, so a wrong guess of the channel costs nothing.
 *   <li>{@link #send}: a fresh code goes to the chosen channel. Resends wait {@code resendCooldown},
 *       a sign-in sends at most {@code maxSends}, and an account gets at most
 *       {@code maxSendsPerUserPerHour} across all its sign-ins.
 *   <li>{@link #verify}: the code is checked. A sign-in allows {@code maxAttempts} tries in all, then
 *       must start again from the password.
 * </ol>
 *
 * <p>Codes are stored only as an HMAC bound to their challenge, compared in constant time, and used at
 * most once. Every limit is enforced by a conditional write, so parallel requests cannot get past it.
 */
public class OtpLoginService {

    private static final Logger log = LoggerFactory.getLogger(OtpLoginService.class);

    static final String SIGN_IN_EXPIRED = "Your sign-in has expired. Please log in again.";
    static final String TOO_MANY_WRONG = "Too many wrong codes. Please log in again.";
    static final String WRONG_CODE = "That code is not right. Check it and try again.";
    static final String CODE_EXPIRED = "That code has expired. Ask for a new one.";

    /** Where a code can be sent, with the destination masked. */
    public record ChannelOption(OtpChannel channel, String destination) {}

    /** The password step's answer. */
    public record Challenge(String challengeId, List<ChannelOption> channels, Duration expiresIn) {
        @Override
        public String toString() {
            return "Challenge[challengeId=<redacted>, channels=" + channels + "]";
        }
    }

    /** A code was sent. */
    public record CodeSent(OtpChannel channel, String destination, Duration codeExpiresIn, Duration resendAfter) {}

    /** The limits from {@code credit.auth.otp}. */
    public record Limits(Duration codeTtl, Duration challengeTtl, int maxAttempts, int maxSends, Duration resendCooldown,
            int maxSendsPerUserPerHour) {}

    private final AuthService auth;
    private final OtpChallengeRepository challenges;
    private final Map<OtpChannel, OtpSender> senders;
    private final Limits limits;
    private final SecretKeySpec codeKey;
    private final Clock clock;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param senders one sender per channel; every channel must have one
     * @param codeKey key for the HMAC codes are stored as (at least 32 bytes)
     */
    public OtpLoginService(AuthService auth, OtpChallengeRepository challenges,
            Map<OtpChannel, OtpSender> senders, Limits limits, byte[] codeKey, Clock clock, AuditLog audit) {
        this.auth = Objects.requireNonNull(auth);
        this.challenges = Objects.requireNonNull(challenges);
        this.senders = new EnumMap<>(senders);
        for (OtpChannel channel : OtpChannel.values()) {
            Objects.requireNonNull(this.senders.get(channel), "no sender for " + channel);
        }
        this.limits = Objects.requireNonNull(limits);
        if (codeKey == null || codeKey.length < 32) {
            throw new IllegalArgumentException("The OTP code key must be at least 32 bytes");
        }
        this.codeKey = new SecretKeySpec(codeKey.clone(), "HmacSHA256");
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
    }

    /**
     * Step 1: checks the password and opens a challenge.
     *
     * @throws AuthExceptions.UnauthenticatedException on a wrong user id or password
     */
    public Challenge start(String userId, String password) {
        User user = auth.checkCredentials(userId, password);
        log.info("User {} passed the password step", user.userId());
        return open(user);
    }

    /**
     * Step 1 for a sign-in whose first factor was checked elsewhere: the Claude connector's consent
     * form, which checks the password and the SSN ({@link AuthService#verifyOwner}).
     *
     * @throws AuthExceptions.UnauthenticatedException if the account no longer exists
     */
    public Challenge startFor(String verifiedUserId) {
        User user = auth.account(verifiedUserId)
                .orElseThrow(() -> new AuthExceptions.UnauthenticatedException(AuthService.BAD_CREDENTIALS));
        return open(user);
    }

    private Challenge open(User user) {
        Instant now = clock.instant();
        String challengeId = randomId();
        challenges.create(OtpChallenge.opened(challengeId, user.userId(), now, now.plus(limits.challengeTtl())));
        List<ChannelOption> channels = new ArrayList<>();
        channels.add(new ChannelOption(OtpChannel.EMAIL, OtpChannel.EMAIL.mask(user.email())));
        if (user.mobilePhone() != null) {
            channels.add(new ChannelOption(OtpChannel.SMS, OtpChannel.SMS.mask(user.mobilePhone())));
        }
        return new Challenge(challengeId, List.copyOf(channels), limits.challengeTtl());
    }

    /**
     * Step 2: sends a new code to the chosen channel; any earlier code of this sign-in stops working.
     *
     * @throws OtpFailedException (restart) if the sign-in expired or is unknown
     * @throws InvalidOtpRequestException if the account has no destination for the channel
     * @throws TooManyCodesException if a limit is reached
     * @throws com.creditagent.web.auth.otp.OtpDeliveryException if the provider refused the message
     */
    public CodeSent send(String challengeId, OtpChannel channel) {
        Objects.requireNonNull(channel, "channel");
        Instant now = clock.instant();
        OtpChallenge challenge = openChallenge(challengeId, now);
        User user = auth.account(challenge.userId()).orElseThrow(() -> OtpFailedException.restart(SIGN_IN_EXPIRED));
        String destination = channel == OtpChannel.EMAIL ? user.email() : user.mobilePhone();
        if (destination == null) {
            throw new InvalidOtpRequestException("There is no mobile number on this account. Choose email instead.");
        }
        if (challenge.sends() >= limits.maxSends()) {
            throw new TooManyCodesException("You have asked for the most codes this sign-in allows. Please log in again.",
                    Duration.between(now, challenge.expiresAt()), true);
        }
        if (challenge.lastSentAt() != null) {
            Instant nextAllowed = challenge.lastSentAt().plus(limits.resendCooldown());
            if (now.isBefore(nextAllowed)) {
                throw tooSoon(Duration.between(now, nextAllowed));
            }
        }
        String code = newCode();
        String codeHash = hash(challengeId, code);
        Instant codeExpiresAt = now.plus(limits.codeTtl());
        if (!challenges.recordSend(challengeId, channel, codeHash, now, codeExpiresAt,
                now.minus(limits.resendCooldown()), limits.maxSends())) {
            // Changed since it was read: gone or expired means start again; otherwise a parallel send won.
            openChallenge(challengeId, now);
            throw tooSoon(limits.resendCooldown());
        }
        // Counted only once this sign-in's own limits passed, so a refused send costs no hourly quota.
        if (!challenges.reserveUserSend(user.userId(), now, limits.maxSendsPerUserPerHour())) {
            release(challengeId, codeHash, challenge, null, now, null);
            log.warn("User {} reached the hourly sign-in code limit", user.userId());
            throw new TooManyCodesException("Too many sign-in codes were sent to this account. Please try again later.",
                    Duration.between(now, now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))), true);
        }
        try {
            senders.get(channel).send(destination, code, limits.codeTtl());
        } catch (OtpDeliveryException e) {
            if (e.refused()) {
                // Certainly not delivered: give the send back, so "try again or choose the other option"
                // works at once.
                release(challengeId, codeHash, challenge, user.userId(), now, e);
                throw e;
            }
            // A timeout, 5xx or throttling may still have delivered it: the send keeps counting (so the
            // limits hold) and its code stays valid in case it arrives late.
            log.warn("Sign-in code for user {} by {} may not have been delivered", user.userId(), channel);
            throw challenge.sends() + 1 >= limits.maxSends() ? e.asLastSend() : e.withRetryAfter(limits.resendCooldown());
        }
        audit.record(AuditLog.Event.LOGIN_CODE_SENT, "user:" + user.userId(), "user:" + user.userId(), "OK",
                Map.of("channel", channel.name()));
        log.info("Sent a sign-in code to user {} by {}", user.userId(), channel);
        return new CodeSent(channel, channel.mask(destination), limits.codeTtl(), limits.resendCooldown());
    }

    /**
     * Step 3: checks the code and, if it is right, opens the session.
     *
     * @throws InvalidOtpRequestException if the code is not six digits or none was sent yet
     * @throws OtpFailedException if the code is wrong or expired, or the sign-in is over
     */
    public AuthService.LoginResult verify(String challengeId, String code) {
        return auth.openSession(verifyCode(challengeId, code));
    }

    /**
     * Step 3 without a web session: checks the code and uses it up. The connector's consent form
     * calls this, then issues an authorization code instead of a login session.
     *
     * @return the id of the user who passed both factors
     * @throws InvalidOtpRequestException if the code is not six digits or none was sent yet
     * @throws OtpFailedException if the code is wrong or expired, or the sign-in is over
     */
    public String verifyCode(String challengeId, String code) {
        Instant now = clock.instant();
        OtpChallenge challenge = openChallenge(challengeId, now);
        if (challenge.codeHash() == null) {
            throw new InvalidOtpRequestException("Choose where to send your code first.");
        }
        String digits = code == null ? "" : code.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{6}")) {
            throw new InvalidOtpRequestException("Enter the 6-digit code.");
        }
        if (!challenges.recordAttempt(challengeId, limits.maxAttempts())) {
            fail(challenge, "attempts");
            challenges.delete(challengeId);
            throw OtpFailedException.restart(TOO_MANY_WRONG);
        }
        int attemptsLeft = limits.maxAttempts() - (challenge.attempts() + 1);
        if (!now.isBefore(challenge.codeExpiresAt())) {
            fail(challenge, "expired");
            throw new OtpFailedException(CODE_EXPIRED, false, null);
        }
        byte[] expected = challenge.codeHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(challengeId, digits).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            fail(challenge, "wrong");
            if (attemptsLeft <= 0) {
                challenges.delete(challengeId);
                throw OtpFailedException.restart(TOO_MANY_WRONG);
            }
            throw new OtpFailedException(WRONG_CODE, false, attemptsLeft);
        }
        if (!challenges.consume(challengeId, challenge.codeHash())) {
            // Used by a parallel request, or replaced by a resend, since it was read.
            throw OtpFailedException.restart(SIGN_IN_EXPIRED);
        }
        return challenge.userId();
    }

    /**
     * Undoes a recorded send (only while its code is still the current one), putting back the code that
     * was current before it, and, with {@code userId}, its hourly count. A failure here is logged and attached to {@code cause}; the TTL clears the rest.
     */
    private void release(String challengeId, String codeHash, OtpChallenge previous, String userId, Instant now,
            RuntimeException cause) {
        try {
            challenges.releaseSend(challengeId, codeHash, previous);
            if (userId != null) {
                challenges.releaseUserSend(userId, now);
            }
        } catch (RuntimeException e) {
            log.warn("Could not release an unsent sign-in code: {}", com.creditagent.common.SafeLogging.describe(e));
            if (cause != null) {
                cause.addSuppressed(e);
            }
        }
    }

    private OtpChallenge openChallenge(String challengeId, Instant now) {
        if (challengeId == null || challengeId.isBlank()) {
            throw OtpFailedException.restart(SIGN_IN_EXPIRED);
        }
        return challenges.find(challengeId)
                .filter(c -> !c.isExpired(now))
                .orElseThrow(() -> OtpFailedException.restart(SIGN_IN_EXPIRED));
    }

    private void fail(OtpChallenge challenge, String reason) {
        audit.record(AuditLog.Event.LOGIN_CODE_FAILED, "user:" + challenge.userId(), "user:" + challenge.userId(), "DENIED",
                Map.of("reason", reason));
        log.info("Wrong or expired sign-in code for user {} ({})", challenge.userId(), reason);
    }

    private static TooManyCodesException tooSoon(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return new TooManyCodesException("Please wait " + seconds + " seconds before asking for another code.",
                Duration.ofSeconds(seconds), false);
    }

    /** Six random digits, leading zeros kept. */
    String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** HMAC-SHA256 of the code bound to its challenge, so a hash says nothing about another challenge's code. */
    String hash(String challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(codeKey);
            return HexFormat.of().formatHex(mac.doFinal((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
