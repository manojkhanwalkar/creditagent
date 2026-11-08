package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthExceptions.UserAlreadyExistsException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    static final String PASSWORD = "correct horse battery staple";

    final InMemoryRepositories.Users users = new InMemoryRepositories.Users();
    final InMemoryRepositories.Sessions sessions = new InMemoryRepositories.Sessions();
    final InMemoryRepositories.MutableClock clock = new InMemoryRepositories.MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
    final AuthService auth = new AuthService(
            users, sessions, new BCryptPasswordEncoder(4),
            new JwtService("auth-test-secret-0123456789abcdef0123456789", "credit-agent", Duration.ofHours(1), clock),
            Duration.ofMinutes(5), clock);

    String register() {
        return auth.register("Avery", PASSWORD, "avery@example.com", "+1 415 555 0100");
    }

    @Test
    void registersWithAHashedPasswordAndNormalisedFields() {
        assertThat(register()).isEqualTo("avery");
        User user = users.find("avery").orElseThrow();
        assertThat(user.passwordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(user.phone()).isEqualTo("+14155550100");
        assertThat(user.toString()).doesNotContain("$2").doesNotContain("example.com");
    }

    @Test
    void duplicateUserIdFailsRegardlessOfCase() {
        register();
        assertThatThrownBy(() -> auth.register("AVERY", "another long passphrase", "a@example.com", "+14155550101"))
                .isInstanceOf(UserAlreadyExistsException.class);
    }

    @Test
    void loginIssuesATokenThatAuthenticates() {
        register();
        AuthService.LoginResult login = auth.login("avery", PASSWORD);
        assertThat(login.idleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(auth.authenticate(login.token()).userId()).isEqualTo("avery");
        assertThat(sessions.byId).hasSize(1);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        register();
        assertThatThrownBy(() -> auth.login("avery", "wrong password here"))
                .isInstanceOf(UnauthenticatedException.class).hasMessage(AuthService.BAD_CREDENTIALS);
        assertThatThrownBy(() -> auth.login("nobody", PASSWORD))
                .isInstanceOf(UnauthenticatedException.class).hasMessage(AuthService.BAD_CREDENTIALS);
        assertThat(sessions.byId).isEmpty();
    }

    @Test
    void sessionEndsAfterFiveIdleMinutes() {
        register();
        String token = auth.login("avery", PASSWORD).token();
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> auth.authenticate(token))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("timed out");
        assertThat(sessions.byId).isEmpty();
    }

    @Test
    void activityKeepsTheSessionAlive() {
        register();
        String token = auth.login("avery", PASSWORD).token();
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(4));
            auth.authenticate(token);
        }
        assertThat(auth.authenticate(token).userId()).isEqualTo("avery");
    }

    @Test
    void frequentRequestsDoNotRewriteTheSessionEveryTime() {
        register();
        String token = auth.login("avery", PASSWORD).token();
        Session before = sessions.byId.values().iterator().next();
        clock.advance(Duration.ofSeconds(10));
        auth.authenticate(token);
        assertThat(sessions.byId.values().iterator().next()).isEqualTo(before);
        clock.advance(AuthService.TOUCH_INTERVAL);
        auth.authenticate(token);
        assertThat(sessions.byId.values().iterator().next().lastActivityAt()).isEqualTo(clock.instant());
    }

    @Test
    void aShortIdleTimeoutStillCountsActivity() {
        AuthService quick = new AuthService(
                users, sessions, new BCryptPasswordEncoder(4),
                new JwtService("auth-test-secret-0123456789abcdef0123456789", "credit-agent", Duration.ofHours(1), clock),
                Duration.ofSeconds(20), clock);
        quick.register("quinn", PASSWORD, "q@example.com", "+14155550102");
        String token = quick.login("quinn", PASSWORD).token();
        clock.advance(Duration.ofSeconds(12));
        quick.authenticate(token);
        clock.advance(Duration.ofSeconds(16));
        assertThat(quick.authenticate(token).userId()).isEqualTo("quinn");
    }

    @Test
    void logoutEndsTheSession() {
        register();
        String token = auth.login("avery", PASSWORD).token();
        auth.logout(auth.authenticate(token));
        assertThatThrownBy(() -> auth.authenticate(token))
                .isInstanceOf(UnauthenticatedException.class).hasMessageContaining("ended");
    }

    @Test
    void sweeperRemovesOnlyExpiredSessions() {
        register();
        auth.login("avery", PASSWORD);
        clock.advance(Duration.ofMinutes(3));
        auth.login("avery", PASSWORD);
        clock.advance(Duration.ofMinutes(3));
        assertThat(auth.cleanUpExpiredSessions()).isEqualTo(1);
        assertThat(sessions.byId).hasSize(1);
    }

    @Test
    void aTokenForAnotherUsersSessionIsRejected() {
        register();
        auth.register("blake", "another long passphrase", "b@example.com", "+14155550101");
        String averySession = sessions.byId.isEmpty() ? auth.login("avery", PASSWORD).token() : null;
        String sessionId = sessions.byId.keySet().iterator().next();
        String forged = new JwtService("auth-test-secret-0123456789abcdef0123456789", "credit-agent", Duration.ofHours(1), clock)
                .issue("blake", sessionId);
        assertThat(averySession).isNotNull();
        assertThatThrownBy(() -> auth.authenticate(forged)).isInstanceOf(UnauthenticatedException.class);
    }
}
