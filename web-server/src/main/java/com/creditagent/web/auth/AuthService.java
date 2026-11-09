package com.creditagent.web.auth;

import com.creditagent.common.Ssn;
import com.creditagent.web.auth.AuthExceptions.InvalidRegistrationException;
import com.creditagent.web.auth.AuthExceptions.SsnAlreadyRegisteredException;
import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthExceptions.UserAlreadyExistsException;
import com.creditagent.web.kyc.DocumentRejectedException;
import com.creditagent.web.kyc.IdentityVerifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Registration, login, per-request authentication and logout. */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A session's {@code lastActivityAt} is rewritten at most this often (or a tenth of the idle
     * timeout, if shorter), so a busy client does not cost a DynamoDB write per request. The idle
     * timeout is therefore enforced to within this much.
     */
    static final Duration TOUCH_INTERVAL = Duration.ofSeconds(30);

    static final String BAD_CREDENTIALS = "User id or password is incorrect.";

    /** What a successful login returns. */
    public record LoginResult(String token, String userId, String ssnMasked, Duration idleTimeout) {}

    /**
     * An account holder who proved, with their password and their SSN, that they own the account; what
     * an OAuth consent is given by.
     */
    public record VerifiedOwner(String userId, String ssnHash, String ssnLastFour) {
        @Override
        public String toString() {
            return "VerifiedOwner[userId=" + userId + "]";
        }
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
    private final SsnHasher ssnHasher;
    private final IdentityVerifier identity;
    private final JwtService jwt;
    private final Duration idleTimeout;
    private final Duration touchInterval;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Hash of a random password, checked when the user id is unknown so both paths cost the same. */
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            SessionRepository sessions,
            PasswordEncoder passwords,
            SsnHasher ssnHasher,
            IdentityVerifier identity,
            JwtService jwt,
            Duration idleTimeout,
            Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.sessions = Objects.requireNonNull(sessions);
        this.passwords = Objects.requireNonNull(passwords);
        this.ssnHasher = Objects.requireNonNull(ssnHasher);
        this.identity = Objects.requireNonNull(identity);
        this.jwt = Objects.requireNonNull(jwt);
        this.idleTimeout = Objects.requireNonNull(idleTimeout);
        Duration tenth = idleTimeout.dividedBy(10);
        this.touchInterval = tenth.compareTo(TOUCH_INTERVAL) < 0 ? tenth : TOUCH_INTERVAL;
        this.clock = Objects.requireNonNull(clock);
        this.dummyHash = passwords.encode(randomId());
    }

    /**
     * Validates and stores a new user. The SSN is not typed in: the KYC provider reads it from the
     * photo of the user's Social Security card, after checking that the photo is a sharp, authentic
     * card. The cheap checks run first, so a bad password or a taken user id never costs a KYC call.
     *
     * @param ssnCardImage the uploaded photo of the card (PNG or JPEG)
     * @return the stored (lower-case) user id
     * @throws AuthExceptions.InvalidRegistrationException if a field breaks a rule or no photo was sent
     * @throws UserAlreadyExistsException if the id is taken
     * @throws DocumentRejectedException if the provider refused the photo, for example as blurred
     * @throws com.creditagent.clients.ServiceUnavailableException if the provider could not be reached
     * @throws SsnAlreadyRegisteredException if another account already has the SSN on the card
     */
    public String register(String userId, String password, String email, String phone, byte[] ssnCardImage) {
        String id = RegistrationPolicy.normaliseUserId(userId);
        RegistrationPolicy.checkPassword(password, id);
        String normalisedEmail = RegistrationPolicy.normaliseEmail(email);
        String normalisedPhone = RegistrationPolicy.normalisePhone(phone);
        if (ssnCardImage == null || ssnCardImage.length == 0) {
            throw new InvalidRegistrationException("Upload a photo of your Social Security card.");
        }
        // Early answer only; the conditional write below is the real check.
        if (users.find(id).isPresent()) {
            throw new UserAlreadyExistsException();
        }
        IdentityVerifier.VerifiedSsn verified = identity.verifySsnCard(ssnCardImage);
        Ssn ssn = verified.ssn();
        User user = new User(
                id,
                passwords.encode(password),
                normalisedEmail,
                normalisedPhone,
                ssnHasher.hash(ssn),
                ssn.digits().substring(5),
                verified.verificationId(),
                clock.instant(),
                ReportStatus.UNLOCKED);
        // The conditional transaction is the real uniqueness check; there is no read-then-write race.
        switch (users.create(user)) {
            case USER_ID_TAKEN -> throw new UserAlreadyExistsException();
            case SSN_TAKEN -> throw new SsnAlreadyRegisteredException();
            case CREATED -> log.info("Registered user {} (KYC {})", id, verified.verificationId());
        }
        return id;
    }

    /** Checks the password and opens a session. @throws UnauthenticatedException on any mismatch */
    public LoginResult login(String userId, String password) {
        User found = checkPassword(userId, password).orElseThrow(() -> {
            log.info("Failed login");
            return new UnauthenticatedException(BAD_CREDENTIALS);
        });
        String id = found.userId();
        Instant now = clock.instant();
        Session session = new Session(
                randomId(), id, found.ssnHash(), found.ssnLastFour(), now, now, now.plus(idleTimeout));
        sessions.save(session);
        log.info("User {} logged in", id);
        return new LoginResult(
                jwt.issue(id, session.sessionId()), id, AuthenticatedUser.mask(found.ssnLastFour()), idleTimeout);
    }

    /** The user, if the password is theirs. Always runs BCrypt, so timing does not reveal unknown ids. */
    private Optional<User> checkPassword(String userId, String password) {
        String id = RegistrationPolicy.canonicalUserId(userId);
        Optional<User> user = id.isEmpty() ? Optional.empty() : users.find(id);
        String candidate = password == null ? "" : password;
        boolean matches = passwords.matches(candidate, user.map(User::passwordHash).orElse(dummyHash));
        return matches ? user : Optional.empty();
    }

    /**
     * Checks the password and that {@code ssn} is the account's own SSN, for the OAuth consent form.
     * The SSN is asked for because only its keyed hash is stored, and the connector needs the SSN
     * itself to fetch the owner's report.
     *
     * @return the owner, or empty on any mismatch (which one is not revealed)
     */
    public Optional<VerifiedOwner> verifyOwner(String userId, String password, Ssn ssn) {
        Optional<User> user = checkPassword(userId, password)
                .filter(u -> ssn != null && ssnHasher.matches(ssn, u.ssnHash()));
        if (user.isEmpty()) {
            log.info("Failed connector sign-in");
            return Optional.empty();
        }
        User found = user.get();
        return Optional.of(new VerifiedOwner(found.userId(), found.ssnHash(), found.ssnLastFour()));
    }

    /** Prefix of the rows that mark an authorization code as used; never a grant or login session id. */
    static final String USED_CODE_PREFIX = "code#";

    /**
     * Turns a consent into a connector grant: an access token's session, with its own expiry.
     *
     * <p>Each authorization code works once. Before the grant is opened, a separate "code used" row
     * ({@code code#<grantId>}) is written with a conditional put. It lives until the code itself
     * expires, so revoking the grant (or a replay revoking it) never makes the code usable again:
     * the marker, not the grant row, is what refuses the second exchange.
     *
     * @param codeExpiresAt when the authorization code stops being valid; the marker lives until then
     * @return false if the code was already used (the caller must revoke the grant it opened)
     */
    public boolean openGrant(String grantId, String userId, Ssn ssn, Instant codeExpiresAt, Instant expiresAt) {
        Instant now = clock.instant();
        String ssnHash = ssnHasher.hash(ssn);
        String lastFour = ssn.digits().substring(5);
        if (!sessions.create(new Session(USED_CODE_PREFIX + grantId, userId, ssnHash, lastFour, now, now, codeExpiresAt))) {
            return false;
        }
        // The id is new (the marker above was), so this never collides; save() keeps it simple.
        sessions.save(new Session(grantId, userId, ssnHash, lastFour, now, now, expiresAt));
        return true;
    }

    /**
     * The owner of an open connector grant. Unlike a login session it is not extended by use: it
     * ends at the expiry it was opened with, or when revoked.
     *
     * @return empty if the grant was revoked, has expired or belongs to someone else
     */
    public Optional<AuthenticatedUser> activeGrant(String grantId, String userId) {
        Instant now = clock.instant();
        return sessions.find(grantId)
                .filter(s -> s.userId().equals(userId) && !s.isExpired(now))
                .map(s -> new AuthenticatedUser(s.userId(), s.sessionId(), s.ssnHash(), s.ssnLastFour()));
    }

    /** Ends a connector grant, so its access token stops working at once. */
    public void revokeGrant(String grantId) {
        sessions.delete(grantId);
        log.info("Connector grant revoked");
    }

    /**
     * Accepts a bearer token only if it is valid and its session is still open and active, then
     * records the activity.
     */
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthenticatedException("Log in to continue.");
        }
        JwtService.Claims claims = jwt.verify(token);
        Instant now = clock.instant();
        Session session = sessions.find(claims.sessionId())
                .filter(s -> s.userId().equals(claims.userId()))
                .orElseThrow(() -> new UnauthenticatedException("Your session has ended. Please log in again."));
        if (session.isExpired(now)) {
            sessions.delete(session.sessionId());
            throw new UnauthenticatedException("Your session timed out. Please log in again.");
        }
        if (Duration.between(session.lastActivityAt(), now).compareTo(touchInterval) >= 0
                && !sessions.touch(session.sessionId(), now, now.plus(idleTimeout))) {
            // Logged out or swept while this request was in flight.
            throw new UnauthenticatedException("Your session has ended. Please log in again.");
        }
        return new AuthenticatedUser(session.userId(), session.sessionId(), session.ssnHash(), session.ssnLastFour());
    }

    /**
     * Allows a credit look-up only for the signed-in user's own SSN. Called before any report is
     * fetched, so a refusal says nothing about whether the other SSN has a report.
     *
     * @throws NotOwnSsnException if {@code ssn} is not the user's registered SSN
     */
    public void requireOwnSsn(AuthenticatedUser user, Ssn ssn) {
        if (!ssnHasher.matches(ssn, user.ssnHash())) {
            log.warn("User {} asked for a credit report that is not theirs", user.userId());
            throw new NotOwnSsnException();
        }
    }

    /**
     * Whether the owner of {@code ssn} has locked their credit report; false when unlocked or when
     * no account has the SSN. For the internal lock check that third-party consumers make.
     */
    public boolean isReportLocked(Ssn ssn) {
        return users.findBySsnHash(ssnHasher.hash(ssn))
                .map(user -> user.reportStatus() == ReportStatus.LOCKED)
                .orElse(false);
    }

    /** The signed-in user's report status. */
    public ReportStatus reportStatus(AuthenticatedUser user) {
        return users.find(user.userId())
                .map(User::reportStatus)
                .orElseThrow(() -> new UnauthenticatedException("Your session has ended. Please log in again."));
    }

    /** Locks or unlocks the signed-in user's credit report. @return the new status */
    public ReportStatus setReportStatus(AuthenticatedUser user, ReportStatus status) {
        Objects.requireNonNull(status, "status");
        if (!users.updateReportStatus(user.userId(), status)) {
            throw new UnauthenticatedException("Your session has ended. Please log in again.");
        }
        log.info("User {} set their credit report to {}", user.userId(), status);
        return status;
    }

    public void logout(AuthenticatedUser user) {
        sessions.delete(user.sessionId());
        log.info("User {} logged out", user.userId());
    }

    /** Deletes sessions past their idle timeout. DynamoDB's TTL also does this, but can lag by days. */
    public int cleanUpExpiredSessions() {
        return sessions.deleteExpired(clock.instant());
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
