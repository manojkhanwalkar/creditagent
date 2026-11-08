package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthExceptions.UserAlreadyExistsException;
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
    public record LoginResult(String token, String userId, Duration idleTimeout) {}

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
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
            JwtService jwt,
            Duration idleTimeout,
            Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.sessions = Objects.requireNonNull(sessions);
        this.passwords = Objects.requireNonNull(passwords);
        this.jwt = Objects.requireNonNull(jwt);
        this.idleTimeout = Objects.requireNonNull(idleTimeout);
        Duration tenth = idleTimeout.dividedBy(10);
        this.touchInterval = tenth.compareTo(TOUCH_INTERVAL) < 0 ? tenth : TOUCH_INTERVAL;
        this.clock = Objects.requireNonNull(clock);
        this.dummyHash = passwords.encode(randomId());
    }

    /**
     * Validates and stores a new user.
     *
     * @return the stored (lower-case) user id
     * @throws AuthExceptions.InvalidRegistrationException if a field breaks a rule
     * @throws UserAlreadyExistsException if the id is taken
     */
    public String register(String userId, String password, String email, String phone) {
        String id = RegistrationPolicy.normaliseUserId(userId);
        RegistrationPolicy.checkPassword(password, id);
        User user = new User(
                id,
                passwords.encode(password),
                RegistrationPolicy.normaliseEmail(email),
                RegistrationPolicy.normalisePhone(phone),
                clock.instant());
        // The conditional write is the real uniqueness check; there is no read-then-write race.
        if (!users.createIfAbsent(user)) {
            throw new UserAlreadyExistsException();
        }
        log.info("Registered user {}", id);
        return id;
    }

    /** Checks the password and opens a session. @throws UnauthenticatedException on any mismatch */
    public LoginResult login(String userId, String password) {
        String id = userId == null ? "" : userId.strip().toLowerCase(java.util.Locale.ROOT);
        Optional<User> user = id.isEmpty() ? Optional.empty() : users.find(id);
        String candidate = password == null ? "" : password;
        // Always run BCrypt, so response time does not reveal whether the user id exists.
        boolean matches = passwords.matches(candidate, user.map(User::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            log.info("Failed login");
            throw new UnauthenticatedException(BAD_CREDENTIALS);
        }
        Instant now = clock.instant();
        Session session = new Session(randomId(), id, now, now, now.plus(idleTimeout));
        sessions.save(session);
        log.info("User {} logged in", id);
        return new LoginResult(jwt.issue(id, session.sessionId()), id, idleTimeout);
    }

    /**
     * Accepts a bearer token only if it is valid and its session is still open and active, then
     * records the activity.
     */
    public AuthenticatedUser authenticate(String token) {
        JwtService.Claims claims = jwt.verify(token);
        Instant now = clock.instant();
        Session session = sessions.find(claims.sessionId())
                .filter(s -> s.userId().equals(claims.userId()))
                .orElseThrow(() -> new UnauthenticatedException("Your session has ended. Please log in again."));
        if (session.isExpired(now)) {
            sessions.delete(session.sessionId());
            throw new UnauthenticatedException("Your session timed out. Please log in again.");
        }
        if (Duration.between(session.lastActivityAt(), now).compareTo(touchInterval) >= 0) {
            sessions.save(session.touchedAt(now, idleTimeout));
        }
        return new AuthenticatedUser(session.userId(), session.sessionId());
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
