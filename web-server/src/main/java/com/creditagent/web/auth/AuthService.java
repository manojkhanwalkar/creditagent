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
        String id = RegistrationPolicy.canonicalUserId(userId);
        Optional<User> user = id.isEmpty() ? Optional.empty() : users.find(id);
        String candidate = password == null ? "" : password;
        // Always run BCrypt, so response time does not reveal whether the user id exists.
        boolean matches = passwords.matches(candidate, user.map(User::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            log.info("Failed login");
            throw new UnauthenticatedException(BAD_CREDENTIALS);
        }
        Instant now = clock.instant();
        User found = user.get();
        Session session = new Session(
                randomId(), id, found.ssnHash(), found.ssnLastFour(), now, now, now.plus(idleTimeout));
        sessions.save(session);
        log.info("User {} logged in", id);
        return new LoginResult(
                jwt.issue(id, session.sessionId()), id, AuthenticatedUser.mask(found.ssnLastFour()), idleTimeout);
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
