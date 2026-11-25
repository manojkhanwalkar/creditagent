package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.AuditLog;
import com.creditagent.web.auth.AuthExceptions.InvalidOtpRequestException;
import com.creditagent.web.auth.AuthExceptions.OtpFailedException;
import com.creditagent.web.auth.AuthExceptions.TooManyCodesException;
import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpDeliveryException;
import com.creditagent.web.notify.NotificationGateway;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The two-step login, against in-memory repositories and a recording (mock) notification-service per channel. */
class OtpLoginServiceTest {

    static final String PASSWORD = "correct horse battery staple";

    final InMemoryRepositories.MutableClock clock = new InMemoryRepositories.MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
    final InMemoryRepositories.Users users = new InMemoryRepositories.Users();
    final InMemoryRepositories.Sessions sessions = new InMemoryRepositories.Sessions();
    final InMemoryRepositories.OtpChallenges challenges = new InMemoryRepositories.OtpChallenges();
    final RecordingNotificationGateway email = new RecordingNotificationGateway();
    final RecordingNotificationGateway sms = new RecordingNotificationGateway();
    final List<String> auditLines = new ArrayList<>();
    /** Keeps each audit record as "event=… actor=… outcome=… {details}". */
    final AuditLog audit = org.mockito.Mockito.mock(AuditLog.class, invocation -> {
        Object[] a = invocation.getArguments();
        auditLines.add("event=" + a[0] + " actor=" + a[1] + " outcome=" + a[3] + " " + (a.length > 4 ? a[4] : "{}"));
        return null;
    });
    AuthService auth;
    OtpLoginService otp;

    @BeforeEach
    void setUp() {
        auth = new AuthService(users, sessions, new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")),
                new FakeIdentityVerifier(), new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock),
                Duration.ofMinutes(5), clock, audit);
        otp = service(OtpTesting.LIMITS);
        auth.register("avery", PASSWORD, "avery@example.com", "206 555 0100", "98101", FakeIdentityVerifier.card("123-45-6789"));
    }

    OtpLoginService service(OtpLoginService.Limits limits) {
        return new OtpLoginService(auth, challenges, gateway(email), limits,
                TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);
    }

    /** Email sends go to {@code emailGateway}, texts to {@link #sms}. */
    NotificationGateway gateway(NotificationGateway emailGateway) {
        return n -> (n.channel() == OtpChannel.EMAIL ? emailGateway : sms).send(n);
    }

    @Test
    void eachSendGoesThroughTheGatewayAsOtpSignInWithItsOwnIdempotencyKey() {
        List<NotificationGateway.Notification> sent = new ArrayList<>();
        NotificationGateway recording = n -> { sent.add(n); gateway(email).send(n); };
        OtpLoginService service = new OtpLoginService(auth, challenges, recording, OtpTesting.LIMITS,
                TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);
        String id = service.start("avery", PASSWORD).challengeId();
        service.send(id, OtpChannel.SMS);
        clock.advance(Duration.ofSeconds(30));
        service.send(id, OtpChannel.SMS);
        assertThat(sent.get(0)).isEqualTo(new NotificationGateway.Notification(OtpChannel.SMS, "+12065550100",
                NotificationGateway.Template.OTP_SIGN_IN, Map.of("code", sms.sent.get(0).code(), "validSeconds", "300"),
                "otp:" + id + ":1"));
        assertThat(sent.get(1).idempotencyKey()).isEqualTo("otp:" + id + ":2");
        String other = service.start("avery", PASSWORD).challengeId();
        service.send(other, OtpChannel.EMAIL);
        assertThat(sent.get(2).idempotencyKey()).isEqualTo("otp:" + other + ":1").doesNotContain(email.lastCode());
    }

    @Test
    void aCodeTtlThatIsNotWholeMinutesIsSentExactlyInSeconds() {
        List<NotificationGateway.Notification> sent = new ArrayList<>();
        OtpLoginService.Limits limits = new OtpLoginService.Limits(Duration.ofSeconds(90), Duration.ofMinutes(10), 5, 3,
                Duration.ofSeconds(30), 10);
        OtpLoginService service = new OtpLoginService(auth, challenges, n -> { sent.add(n); gateway(email).send(n); },
                limits, TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);
        service.send(service.start("avery", PASSWORD).challengeId(), OtpChannel.EMAIL);
        assertThat(sent.get(0).params()).containsEntry("validSeconds", "90");
        assertThat(email.sent.get(0).validFor()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void thePasswordStepOffersBothChannelsMaskedAndOpensNoSession() {
        OtpLoginService.Challenge challenge = otp.start("Avery", PASSWORD);

        assertThat(challenge.channels()).containsExactly(
                new OtpLoginService.ChannelOption(OtpChannel.EMAIL, "a***@example.com"),
                new OtpLoginService.ChannelOption(OtpChannel.SMS, "***-***-0100"));
        assertThat(challenge.expiresIn()).isEqualTo(Duration.ofMinutes(10));
        assertThat(challenge.challengeId()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(challenge.toString()).doesNotContain(challenge.challengeId());
        assertThat(sessions.byId).isEmpty();
        assertThat(email.sent).isEmpty();
        assertThat(sms.sent).isEmpty();
    }

    @Test
    void anAccountWithoutAMobileNumberIsOfferedEmailOnly() {
        User avery = users.byId.remove("avery");
        users.byId.put("old", new User("old", avery.passwordHash(), "old@example.com", "h-old", "6789", "kyc",
                avery.createdAt(), ReportStatus.UNLOCKED));

        OtpLoginService.Challenge challenge = otp.start("old", PASSWORD);

        assertThat(challenge.channels()).extracting(OtpLoginService.ChannelOption::channel).containsExactly(OtpChannel.EMAIL);
        assertThatThrownBy(() -> otp.send(challenge.challengeId(), OtpChannel.SMS))
                .isInstanceOf(InvalidOtpRequestException.class).hasMessageContaining("no mobile number");
        assertThat(sms.sent).isEmpty();
    }

    @Test
    void aWrongPasswordGetsTheGenericAnswerAndNoChallenge() {
        assertThatThrownBy(() -> otp.start("avery", "not the password at all"))
                .isInstanceOf(UnauthenticatedException.class).hasMessage(AuthService.BAD_CREDENTIALS);
        assertThatThrownBy(() -> otp.start("nobody", PASSWORD))
                .isInstanceOf(UnauthenticatedException.class).hasMessage(AuthService.BAD_CREDENTIALS);
        assertThat(challenges.byId).isEmpty();
    }

    @Test
    void theRightEmailCodeOpensASessionOnce() {
        String id = otp.start("avery", PASSWORD).challengeId();
        OtpLoginService.CodeSent sent = otp.send(id, OtpChannel.EMAIL);

        assertThat(sent).isEqualTo(new OtpLoginService.CodeSent(
                OtpChannel.EMAIL, "a***@example.com", Duration.ofMinutes(5), Duration.ofSeconds(30)));
        assertThat(email.last().destination()).isEqualTo("avery@example.com");
        assertThat(email.last().code()).matches("\\d{6}");
        assertThat(email.last().validFor()).isEqualTo(Duration.ofMinutes(5));
        // Only a keyed hash is stored, never the code.
        assertThat(challenges.byId.get(id).codeHash()).doesNotContain(email.lastCode()).hasSize(64);

        AuthService.LoginResult result = otp.verify(id, email.lastCode());

        assertThat(auth.authenticate(result.token()).userId()).isEqualTo("avery");
        assertThat(result.ssnMasked()).isEqualTo("***-**-6789");
        assertThat(users.byId.get("avery").lastLoginAt()).isEqualTo(clock.instant());
        assertThat(challenges.byId).isEmpty();
        assertThatThrownBy(() -> otp.verify(id, email.lastCode()))
                .isInstanceOf(OtpFailedException.class)
                .satisfies(e -> assertThat(((OtpFailedException) e).restart()).isTrue());
        assertThat(sessions.byId).hasSize(1);
        assertThat(auditLines).anyMatch(l -> l.contains("event=LOGIN_CODE_SENT") && l.contains("channel=EMAIL"))
                .anyMatch(l -> l.contains("event=LOGIN_SUCCEEDED") && l.contains("user:avery"))
                .noneMatch(l -> l.contains(email.lastCode()) || l.contains("avery@example.com"));
    }

    @Test
    void theRightSmsCodeOpensASession() {
        String id = otp.start("avery", PASSWORD).challengeId();
        assertThat(otp.send(id, OtpChannel.SMS).destination()).isEqualTo("***-***-0100");
        assertThat(sms.last().destination()).isEqualTo("+12065550100");
        assertThat(email.sent).isEmpty();

        assertThat(otp.verify(id, " " + sms.lastCode().substring(0, 3) + "-" + sms.lastCode().substring(3) + " ").userId())
                .isEqualTo("avery");
    }

    @Test
    void aWrongCodeSaysHowManyTriesAreLeftThenEndsTheSignIn() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        String wrong = email.lastCode().equals("000000") ? "111111" : "000000";

        for (int left = 4; left >= 1; left--) {
            int expected = left;
            assertThatThrownBy(() -> otp.verify(id, wrong))
                    .isInstanceOf(OtpFailedException.class).hasMessage(OtpLoginService.WRONG_CODE)
                    .satisfies(e -> {
                        assertThat(((OtpFailedException) e).attemptsLeft()).isEqualTo(expected);
                        assertThat(((OtpFailedException) e).restart()).isFalse();
                    });
        }
        assertThatThrownBy(() -> otp.verify(id, wrong))
                .isInstanceOf(OtpFailedException.class).hasMessage(OtpLoginService.TOO_MANY_WRONG)
                .satisfies(e -> assertThat(((OtpFailedException) e).restart()).isTrue());
        // Even the right code no longer works: the sign-in is gone.
        assertThatThrownBy(() -> otp.verify(id, email.lastCode())).isInstanceOf(OtpFailedException.class);
        assertThat(sessions.byId).isEmpty();
        assertThat(auditLines).filteredOn(l -> l.contains("event=LOGIN_CODE_FAILED")).hasSize(5);
    }

    @Test
    void attemptsAreCountedEvenWhenAParallelRequestUsedTheLastOne() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        // Another request used every attempt after this one read the challenge.
        OtpChallenge c = challenges.byId.get(id);
        challenges.byId.put(id, new OtpChallenge(c.challengeId(), c.userId(), c.createdAt(), c.expiresAt(), c.channel(),
                c.codeHash(), c.codeExpiresAt(), c.lastSentAt(), c.sends(), 5));

        assertThatThrownBy(() -> otp.verify(id, email.lastCode()))
                .isInstanceOf(OtpFailedException.class).hasMessage(OtpLoginService.TOO_MANY_WRONG);
        assertThat(challenges.byId).doesNotContainKey(id);
        assertThat(sessions.byId).isEmpty();
    }

    @Test
    void anExpiredCodeIsRefusedButANewOneWorks() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        String old = email.lastCode();
        clock.advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> otp.verify(id, old))
                .isInstanceOf(OtpFailedException.class).hasMessage(OtpLoginService.CODE_EXPIRED)
                .satisfies(e -> assertThat(((OtpFailedException) e).restart()).isFalse());

        otp.send(id, OtpChannel.SMS);
        assertThat(otp.verify(id, sms.lastCode()).userId()).isEqualTo("avery");
    }

    @Test
    void aResendReplacesTheEarlierCode() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        String first = email.lastCode();
        clock.advance(Duration.ofSeconds(30));
        otp.send(id, OtpChannel.SMS);
        String second = sms.lastCode();

        if (!first.equals(second)) {
            assertThatThrownBy(() -> otp.verify(id, first)).isInstanceOf(OtpFailedException.class)
                    .hasMessage(OtpLoginService.WRONG_CODE);
        }
        assertThat(otp.verify(id, second).userId()).isEqualTo("avery");
    }

    @Test
    void aCodeReplacedWhileItWasCheckedOpensNoSession() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        String code = email.lastCode();
        // A resend lands between this request's read and its consume.
        InMemoryRepositories.OtpChallenges racing = new InMemoryRepositories.OtpChallenges() {
            @Override
            public boolean consume(String challengeId, String codeHash) {
                return false;
            }
        };
        racing.byId.putAll(challenges.byId);
        OtpLoginService raced = new OtpLoginService(auth, racing, gateway(email),
                OtpTesting.LIMITS, TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);

        assertThatThrownBy(() -> raced.verify(id, code)).isInstanceOf(OtpFailedException.class)
                .hasMessage(OtpLoginService.SIGN_IN_EXPIRED);
        assertThat(sessions.byId).isEmpty();
    }

    @Test
    void resendsWaitForTheCooldownAndAreCappedPerSignIn() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        clock.advance(Duration.ofSeconds(10));

        assertThatThrownBy(() -> otp.send(id, OtpChannel.EMAIL))
                .isInstanceOf(TooManyCodesException.class).hasMessageContaining("wait 20 seconds")
                .satisfies(e -> {
                    assertThat(((TooManyCodesException) e).retryAfter()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(((TooManyCodesException) e).restart()).isFalse();
                });

        clock.advance(Duration.ofSeconds(20));
        otp.send(id, OtpChannel.EMAIL);
        clock.advance(Duration.ofSeconds(30));
        otp.send(id, OtpChannel.SMS);
        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> otp.send(id, OtpChannel.EMAIL))
                .isInstanceOf(TooManyCodesException.class).hasMessageContaining("most codes")
                .satisfies(e -> assertThat(((TooManyCodesException) e).restart()).isTrue());
        assertThat(email.sent.size() + sms.sent.size()).isEqualTo(3);
    }

    @Test
    void aSendThatLosesARaceIsRefusedAndSendsNothing() {
        String id = otp.start("avery", PASSWORD).challengeId();
        InMemoryRepositories.OtpChallenges racing = new InMemoryRepositories.OtpChallenges() {
            @Override
            public synchronized boolean recordSend(String challengeId, OtpChannel channel, String codeHash, Instant now,
                    Instant codeExpiresAt, Instant notSentSince, int maxSends) {
                return false;
            }
        };
        racing.byId.putAll(challenges.byId);
        OtpLoginService raced = new OtpLoginService(auth, racing, gateway(email),
                OtpTesting.LIMITS, TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);

        assertThatThrownBy(() -> raced.send(id, OtpChannel.EMAIL)).isInstanceOf(TooManyCodesException.class);
        assertThat(email.sent).isEmpty();
        assertThat(racing.userSends).isEmpty(); // a refused send costs no hourly quota
    }

    @Test
    void anAccountGetsALimitedNumberOfCodesAnHourAcrossSignIns() {
        OtpLoginService strict = service(new OtpLoginService.Limits(
                Duration.ofMinutes(5), Duration.ofMinutes(10), 5, 3, Duration.ofSeconds(30), 2));
        strict.send(strict.start("avery", PASSWORD).challengeId(), OtpChannel.EMAIL);
        strict.send(strict.start("avery", PASSWORD).challengeId(), OtpChannel.EMAIL);
        clock.advance(Duration.ofMinutes(15));
        String third = strict.start("avery", PASSWORD).challengeId();

        assertThatThrownBy(() -> strict.send(third, OtpChannel.SMS))
                .isInstanceOf(TooManyCodesException.class).hasMessageContaining("Too many sign-in codes")
                .satisfies(e -> assertThat(((TooManyCodesException) e).retryAfter()).isEqualTo(Duration.ofMinutes(45)));
        assertThat(sms.sent).isEmpty();

        clock.advance(Duration.ofMinutes(45)); // the next clock hour
        strict.send(strict.start("avery", PASSWORD).challengeId(), OtpChannel.SMS);
        assertThat(sms.sent).hasSize(1);
    }

    @Test
    void anExpiredOrUnknownSignInMustStartAgain() {
        String id = otp.start("avery", PASSWORD).challengeId();
        clock.advance(Duration.ofMinutes(10));

        for (String bad : new String[] {id, "made-up", "", null}) {
            assertThatThrownBy(() -> otp.send(bad, OtpChannel.EMAIL)).isInstanceOf(OtpFailedException.class)
                    .hasMessage(OtpLoginService.SIGN_IN_EXPIRED);
            assertThatThrownBy(() -> otp.verify(bad, "123456")).isInstanceOf(OtpFailedException.class)
                    .hasMessage(OtpLoginService.SIGN_IN_EXPIRED);
        }
        assertThat(email.sent).isEmpty();
    }

    @Test
    void anAccountDeletedBetweenTheStepsGetsNoCodeAndNoSession() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        User avery = users.byId.remove("avery");

        assertThatThrownBy(() -> otp.verify(id, email.lastCode())).isInstanceOf(UnauthenticatedException.class);
        assertThat(sessions.byId).isEmpty();

        users.byId.put("avery", avery);
        String next = otp.start("avery", PASSWORD).challengeId();
        users.byId.remove("avery");
        assertThatThrownBy(() -> otp.send(next, OtpChannel.EMAIL)).isInstanceOf(OtpFailedException.class);
    }

    @Test
    void malformedCodesAndCodesBeforeASendAreRefusedWithoutUsingAnAttempt() {
        String id = otp.start("avery", PASSWORD).challengeId();
        assertThatThrownBy(() -> otp.verify(id, "123456"))
                .isInstanceOf(InvalidOtpRequestException.class).hasMessageContaining("send your code first");

        otp.send(id, OtpChannel.EMAIL);
        for (String bad : new String[] {null, "", "12345", "1234567", "12a456"}) {
            assertThatThrownBy(() -> otp.verify(id, bad)).isInstanceOf(InvalidOtpRequestException.class)
                    .hasMessage("Enter the 6-digit code.");
        }
        assertThat(challenges.byId.get(id).attempts()).isZero();
    }

    /** The service answered 422: the provider rejected the message outright. */
    static OtpDeliveryException rejected(OtpChannel channel) {
        return new OtpDeliveryException(channel, new IllegalStateException("refused (422)"), true);
    }

    /** A failure after which the message may still have been delivered (a timeout, a 5xx). */
    static OtpDeliveryException uncertain(OtpChannel channel) {
        return new OtpDeliveryException(channel, new IllegalStateException("timed out"), false);
    }

    @Test
    void aSendThatMayHaveBeenDeliveredStillCountsAndItsCodeStillWorks() {
        String id = otp.start("avery", PASSWORD).challengeId();
        // The provider takes the message, then the call times out.
        OtpSenderThatDeliversThenFails late = new OtpSenderThatDeliversThenFails();
        OtpLoginService service = new OtpLoginService(auth, challenges, gateway(late),
                OtpTesting.LIMITS, TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);

        assertThatThrownBy(() -> service.send(id, OtpChannel.EMAIL)).isInstanceOf(OtpDeliveryException.class)
                .satisfies(e -> {
                    assertThat(((OtpDeliveryException) e).refused()).isFalse();
                    assertThat(((OtpDeliveryException) e).retryAfter()).isEqualTo(Duration.ofSeconds(30));
                });
        // Still counted: the cooldown holds, so failures cannot be used to send without limit.
        assertThat(challenges.byId.get(id).sends()).isEqualTo(1);
        assertThat(challenges.userSends.values()).containsOnly(1);
        assertThatThrownBy(() -> service.send(id, OtpChannel.SMS)).isInstanceOf(TooManyCodesException.class);
        // And the code that did arrive works.
        assertThat(service.verify(id, late.code).userId()).isEqualTo("avery");
    }

    @Test
    void repeatedUncertainFailuresStillHitTheLimits() {
        email.failure = uncertain(OtpChannel.EMAIL);
        String id = otp.start("avery", PASSWORD).challengeId();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> otp.send(id, OtpChannel.EMAIL)).isInstanceOf(OtpDeliveryException.class);
            clock.advance(Duration.ofSeconds(30));
        }
        assertThatThrownBy(() -> otp.send(id, OtpChannel.EMAIL)).isInstanceOf(TooManyCodesException.class)
                .hasMessageContaining("most codes");
    }

    @Test
    void anUncertainLastSendDoesNotPromiseAnotherCode() {
        email.failure = uncertain(OtpChannel.EMAIL);
        String id = otp.start("avery", PASSWORD).challengeId();
        for (int i = 1; i <= 3; i++) {
            int send = i;
            assertThatThrownBy(() -> otp.send(id, OtpChannel.EMAIL)).isInstanceOf(OtpDeliveryException.class)
                    .satisfies(e -> {
                        OtpDeliveryException failure = (OtpDeliveryException) e;
                        assertThat(failure.lastSend()).isEqualTo(send == 3);
                        assertThat(failure.retryAfter()).isEqualTo(send == 3 ? null : Duration.ofSeconds(30));
                        assertThat(OtpProblems.notSent(failure).getBody().getDetail())
                                .contains(send == 3 ? "log in again" : "ask for a new one in 30 seconds");
                    });
            clock.advance(Duration.ofSeconds(30));
        }
    }

    /** Delivers the message, then fails as a timed-out call would. */
    static final class OtpSenderThatDeliversThenFails implements NotificationGateway {
        String code;

        @Override
        public void send(Notification n) {
            this.code = n.params().get("code");
            throw uncertain(OtpChannel.EMAIL);
        }
    }

    @Test
    void aProviderRefusalReachesTheCallerAndGivesTheSendBack() {
        String id = otp.start("avery", PASSWORD).challengeId();
        email.failure = rejected(OtpChannel.EMAIL);

        assertThatThrownBy(() -> otp.send(id, OtpChannel.EMAIL)).isInstanceOf(OtpDeliveryException.class);
        assertThat(auditLines).noneMatch(l -> l.contains("LOGIN_CODE_SENT"));
        OtpChallenge after = challenges.byId.get(id);
        assertThat(after.sends()).isZero();
        assertThat(after.codeHash()).isNull();
        assertThat(after.lastSentAt()).isNull();
        assertThat(challenges.userSends.values()).containsOnly(0);

        // "Try again, or choose the other option": at once, no cooldown.
        otp.send(id, OtpChannel.SMS);
        assertThat(otp.verify(id, sms.lastCode()).userId()).isEqualTo("avery");
    }

    @Test
    void aReleaseThatFailsIsAttachedToTheDeliveryError() {
        InMemoryRepositories.OtpChallenges broken = new InMemoryRepositories.OtpChallenges() {
            @Override
            public synchronized void releaseSend(String challengeId, String codeHash, OtpChallenge previous) {
                throw new IllegalStateException("DynamoDB down");
            }
        };
        OtpLoginService service = new OtpLoginService(auth, broken, gateway(email),
                OtpTesting.LIMITS, TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);
        String id = service.start("avery", PASSWORD).challengeId();
        email.failure = rejected(OtpChannel.EMAIL);

        assertThatThrownBy(() -> service.send(id, OtpChannel.EMAIL)).isInstanceOf(OtpDeliveryException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }

    @Test
    void theHourlyLimitRefusalLeavesTheSignInAsItWas() {
        OtpLoginService strict = service(new OtpLoginService.Limits(
                Duration.ofMinutes(5), Duration.ofMinutes(10), 5, 3, Duration.ofSeconds(30), 1));
        strict.send(strict.start("avery", PASSWORD).challengeId(), OtpChannel.EMAIL);
        String id = strict.start("avery", PASSWORD).challengeId();

        assertThatThrownBy(() -> strict.send(id, OtpChannel.EMAIL)).isInstanceOf(TooManyCodesException.class);
        assertThat(challenges.byId.get(id).sends()).isZero();
        assertThat(challenges.byId.get(id).codeHash()).isNull();
    }

    @Test
    void aRefusedResendKeepsTheCodeTheUserAlreadyHas() {
        String id = otp.start("avery", PASSWORD).challengeId();
        otp.send(id, OtpChannel.EMAIL);
        String first = email.lastCode();
        clock.advance(Duration.ofSeconds(30));
        sms.failure = rejected(OtpChannel.SMS);

        assertThatThrownBy(() -> otp.send(id, OtpChannel.SMS)).isInstanceOf(OtpDeliveryException.class);

        assertThat(otp.verify(id, first).userId()).isEqualTo("avery");
    }

    @Test
    void anHourlyLimitRefusedResendKeepsTheCodeTheUserAlreadyHas() {
        OtpLoginService strict = service(new OtpLoginService.Limits(
                Duration.ofMinutes(5), Duration.ofMinutes(10), 5, 3, Duration.ofSeconds(30), 1));
        String id = strict.start("avery", PASSWORD).challengeId();
        strict.send(id, OtpChannel.EMAIL);
        String first = email.lastCode();
        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> strict.send(id, OtpChannel.EMAIL)).isInstanceOf(TooManyCodesException.class);

        assertThat(strict.verify(id, first).userId()).isEqualTo("avery");
    }

    @Test
    void aSignInThatExpiresWhileACodeIsBeingSentMustStartAgain() {
        String id = otp.start("avery", PASSWORD).challengeId();
        InMemoryRepositories.OtpChallenges expiring = new InMemoryRepositories.OtpChallenges() {
            @Override
            public synchronized boolean recordSend(String challengeId, OtpChannel channel, String codeHash, Instant now,
                    Instant codeExpiresAt, Instant notSentSince, int maxSends) {
                byId.remove(challengeId); // swept between the read and the write
                return false;
            }
        };
        expiring.byId.putAll(challenges.byId);
        OtpLoginService raced = new OtpLoginService(auth, expiring, gateway(email),
                OtpTesting.LIMITS, TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8), clock, audit);

        assertThatThrownBy(() -> raced.send(id, OtpChannel.EMAIL)).isInstanceOf(OtpFailedException.class)
                .satisfies(e -> assertThat(((OtpFailedException) e).restart()).isTrue());
        assertThat(expiring.userSends).isEmpty();
        assertThat(email.sent).isEmpty();
    }

    @Test
    void theConnectorStartsASignInForAnOwnerItAlreadyCheckedAndGetsNoSession() {
        OtpLoginService.Challenge challenge = otp.startFor("avery");
        assertThat(challenge.channels()).hasSize(2);
        otp.send(challenge.challengeId(), OtpChannel.EMAIL);

        assertThat(otp.verifyCode(challenge.challengeId(), email.lastCode())).isEqualTo("avery");
        assertThat(sessions.byId).isEmpty();
        assertThatThrownBy(() -> otp.startFor("nobody")).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void codesAreSixDigitsAndHashesAreBoundToTheirChallenge() {
        for (int i = 0; i < 200; i++) {
            assertThat(otp.newCode()).matches("\\d{6}");
        }
        assertThat(otp.hash("a", "123456")).isEqualTo(otp.hash("a", "123456"))
                .isNotEqualTo(otp.hash("b", "123456"))
                .isNotEqualTo(otp.hash("a", "123457"));
    }

    @Test
    void theServiceNeedsAGatewayAndAStrongKey() {
        byte[] key = TestSecrets.of("otp").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new OtpLoginService(auth, challenges, null, OtpTesting.LIMITS,
                key, clock, audit)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OtpLoginService(auth, challenges, gateway(email),
                OtpTesting.LIMITS, new byte[16], clock, audit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpLoginService(auth, challenges, gateway(email),
                OtpTesting.LIMITS, null, clock, audit)).isInstanceOf(IllegalArgumentException.class);
    }
}
