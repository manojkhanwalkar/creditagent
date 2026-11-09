package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthExceptions.UserAlreadyExistsException;
import com.creditagent.web.kyc.DocumentRejectedException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    static final String PASSWORD = "correct horse battery staple";
    static final String AVERY_SSN = "123-45-6789";
    static final SsnHasher HASHER = new SsnHasher(TestSecrets.of("ssn"));

    final InMemoryRepositories.Users users = new InMemoryRepositories.Users();
    final InMemoryRepositories.Sessions sessions = new InMemoryRepositories.Sessions();
    final InMemoryRepositories.MutableClock clock = new InMemoryRepositories.MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
    final FakeIdentityVerifier kyc = new FakeIdentityVerifier();
    final AuthService auth = new AuthService(
            users, sessions, new BCryptPasswordEncoder(4), HASHER, kyc,
            new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock),
            Duration.ofMinutes(5), clock);

    String register() {
        return auth.register("Avery", PASSWORD, "avery@example.com", "+1 415 555 0100", FakeIdentityVerifier.card(AVERY_SSN));
    }

    @Test
    void registersWithAHashedPasswordAndNormalisedFields() {
        assertThat(register()).isEqualTo("avery");
        User user = users.find("avery").orElseThrow();
        assertThat(user.passwordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(user.phone()).isEqualTo("+14155550100");
        assertThat(user.toString()).doesNotContain("$2").doesNotContain("example.com").doesNotContain("6789");
        // The SSN itself is never stored: only its keyed hash and last four digits.
        assertThat(user.ssnHash()).isEqualTo(HASHER.hash(com.creditagent.common.Ssn.parse(AVERY_SSN)))
                .doesNotContain("123456789");
        assertThat(user.ssnLastFour()).isEqualTo("6789");
    }

    @Test
    void anSsnCanOnlyBelongToOneAccount() {
        register();
        assertThatThrownBy(() -> auth.register("blake", "another long passphrase", "b@example.com", "+14155550101", FakeIdentityVerifier.card("123456789")))
                .isInstanceOf(AuthExceptions.SsnAlreadyRegisteredException.class);
        assertThat(users.find("blake")).isEmpty();
    }

    @Test
    void theSsnIsTheOneTheKycProviderReadFromTheCard() {
        register();
        User user = users.find("avery").orElseThrow();
        assertThat(user.kycVerificationId()).isEqualTo("kyc_test_1");
        assertThat(user.toString()).doesNotContain("kyc_test");
        assertThat(HASHER.matches(com.creditagent.common.Ssn.parse(AVERY_SSN), user.ssnHash())).isTrue();
    }

    @Test
    void registrationNeedsACardPhoto() {
        assertThatThrownBy(() -> auth.register("blake", "another long passphrase", "b@example.com", "+14155550101", null))
                .isInstanceOf(AuthExceptions.InvalidRegistrationException.class)
                .hasMessageContaining("Social Security card");
        assertThatThrownBy(() -> auth.register("blake", "another long passphrase", "b@example.com", "+14155550101", new byte[0]))
                .isInstanceOf(AuthExceptions.InvalidRegistrationException.class);
        assertThat(kyc.calls).hasValue(0);
    }

    @Test
    void aBlurryPhotoIsRejectedAndTheUserCanRetry() {
        assertThatThrownBy(() -> auth.register("blake", "another long passphrase", "b@example.com", "+14155550101",
                        FakeIdentityVerifier.blurry()))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessageContaining("blurry")
                .extracting(e -> ((DocumentRejectedException) e).reason())
                .isEqualTo(DocumentRejectedException.IMAGE_BLURRY);
        assertThat(users.find("blake")).isEmpty();

        assertThat(auth.register("blake", "another long passphrase", "b@example.com", "+14155550101",
                FakeIdentityVerifier.card("234-56-7890"))).isEqualTo("blake");
    }

    @Test
    void cheapChecksRunBeforeTheKycCall() {
        register();
        assertThatThrownBy(() -> auth.register("blake", "Password1234", "b@example.com", "+14155550101",
                        FakeIdentityVerifier.card("234-56-7890")))
                .isInstanceOf(AuthExceptions.InvalidRegistrationException.class);
        assertThatThrownBy(() -> auth.register("blake", "another long passphrase", "not-an-email", "+14155550101",
                        FakeIdentityVerifier.card("234-56-7890")))
                .isInstanceOf(AuthExceptions.InvalidRegistrationException.class);
        assertThatThrownBy(() -> auth.register("avery", "another long passphrase", "a@example.com", "+14155550101",
                        FakeIdentityVerifier.card("234-56-7890")))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThat(kyc.calls).hasValue(1); // only the first, successful registration
    }

    @Test
    void usersMayOnlyLookUpTheirOwnSsn() {
        register();
        AuthenticatedUser avery = auth.authenticate(auth.login("avery", PASSWORD).token());
        assertThat(avery.ssnMasked()).isEqualTo("***-**-6789");
        auth.requireOwnSsn(avery, com.creditagent.common.Ssn.parse("123456789"));
        assertThatThrownBy(() -> auth.requireOwnSsn(avery, com.creditagent.common.Ssn.parse("234-56-7890")))
                .isInstanceOf(NotOwnSsnException.class)
                .hasMessage(NotOwnSsnException.MESSAGE);
    }

    @Test
    void aMissingTokenIsRefused() {
        assertThatThrownBy(() -> auth.authenticate("")).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> auth.authenticate(null)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void duplicateUserIdFailsRegardlessOfCase() {
        register();
        assertThatThrownBy(() -> auth.register("AVERY", "another long passphrase", "a@example.com", "+14155550101", FakeIdentityVerifier.card("234-56-7890")))
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
                users, sessions, new BCryptPasswordEncoder(4), HASHER, kyc,
                new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock),
                Duration.ofSeconds(20), clock);
        quick.register("quinn", PASSWORD, "q@example.com", "+14155550102", FakeIdentityVerifier.card("345-67-8901"));
        String token = quick.login("quinn", PASSWORD).token();
        clock.advance(Duration.ofSeconds(12));
        quick.authenticate(token);
        clock.advance(Duration.ofSeconds(16));
        assertThat(quick.authenticate(token).userId()).isEqualTo("quinn");
    }

    @Test
    void aRequestInFlightDuringLogoutDoesNotBringTheSessionBack() {
        register();
        String token = auth.login("avery", PASSWORD).token();
        // The request has read the session; logout deletes it before the request records activity.
        InMemoryRepositories.Sessions racing = new InMemoryRepositories.Sessions() {
            @Override
            public java.util.Optional<Session> find(String sessionId) {
                java.util.Optional<Session> found = sessions.find(sessionId);
                sessions.delete(sessionId);
                return found;
            }

            @Override
            public boolean touch(String sessionId, Instant last, Instant expires) {
                return sessions.touch(sessionId, last, expires);
            }
        };
        AuthService racy = new AuthService(users, racing, new BCryptPasswordEncoder(4), HASHER, kyc,
                new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock),
                Duration.ofMinutes(5), clock);
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> racy.authenticate(token)).isInstanceOf(UnauthenticatedException.class);
        assertThat(sessions.byId).isEmpty();
    }

    @Test
    void loginIgnoresCaseAndSpaces() {
        register();
        assertThat(auth.login("  AVERY ", PASSWORD).userId()).isEqualTo("avery");
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
        auth.register("blake", "another long passphrase", "b@example.com", "+14155550101", FakeIdentityVerifier.card("234-56-7890"));
        String averySession = sessions.byId.isEmpty() ? auth.login("avery", PASSWORD).token() : null;
        String sessionId = sessions.byId.keySet().iterator().next();
        String forged = new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock)
                .issue("blake", sessionId);
        assertThat(averySession).isNotNull();
        assertThatThrownBy(() -> auth.authenticate(forged)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void aNewUsersReportStartsUnlockedAndCanBeLockedAndUnlocked() {
        register();
        AuthenticatedUser avery = auth.authenticate(auth.login("avery", PASSWORD).token());
        assertThat(auth.reportStatus(avery)).isEqualTo(ReportStatus.UNLOCKED);

        assertThat(auth.setReportStatus(avery, ReportStatus.LOCKED)).isEqualTo(ReportStatus.LOCKED);
        assertThat(auth.reportStatus(avery)).isEqualTo(ReportStatus.LOCKED);
        assertThat(users.find("avery").orElseThrow().reportStatus()).isEqualTo(ReportStatus.LOCKED);

        auth.setReportStatus(avery, ReportStatus.UNLOCKED);
        assertThat(auth.reportStatus(avery)).isEqualTo(ReportStatus.UNLOCKED);
    }

    @Test
    void aDeletedUserCannotChangeTheirStatus() {
        register();
        AuthenticatedUser avery = auth.authenticate(auth.login("avery", PASSWORD).token());
        users.byId.remove("avery");
        assertThatThrownBy(() -> auth.setReportStatus(avery, ReportStatus.LOCKED)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> auth.reportStatus(avery)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void storedValuesMapToAStatus() {
        assertThat(ReportStatus.fromStored("LOCKED")).isEqualTo(ReportStatus.LOCKED);
        assertThat(ReportStatus.fromStored("UNLOCKED")).isEqualTo(ReportStatus.UNLOCKED);
        // Missing (an older account): unlocked. Anything unexpected: locked, so the lock fails closed.
        assertThat(ReportStatus.fromStored(null)).isEqualTo(ReportStatus.UNLOCKED);
        assertThat(ReportStatus.fromStored("garbage")).isEqualTo(ReportStatus.LOCKED);
        assertThat(ReportStatus.fromStored("locked")).isEqualTo(ReportStatus.LOCKED);
    }

    @Test
    void thirdPartiesSeeALockOnlyForALockedRegisteredSsn() {
        register();
        com.creditagent.common.Ssn averySsn = com.creditagent.common.Ssn.parse(AVERY_SSN);
        assertThat(auth.isReportLocked(averySsn)).isFalse();

        AuthenticatedUser avery = auth.authenticate(auth.login("avery", PASSWORD).token());
        auth.setReportStatus(avery, ReportStatus.LOCKED);
        assertThat(auth.isReportLocked(averySsn)).isTrue();

        // No account for this SSN: not locked, and indistinguishable from an unlocked account.
        assertThat(auth.isReportLocked(com.creditagent.common.Ssn.parse("876-54-3210"))).isFalse();
    }
}
