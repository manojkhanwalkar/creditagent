package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import com.creditagent.web.CreditScoreService;
import com.creditagent.web.WebServerProperties;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Register, log in, use the token on the score API, log out: through the real filter chain. */
@WebMvcTest(controllers = {AuthController.class, ReportStatusController.class})
@Import(AuthControllerTest.Wiring.class)
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class AuthControllerTest {

    static final String PASSWORD = "correct horse battery staple";

    /** A registration form for {@code userId} with {@code card} as the SSN card photo. */
    static MockMultipartHttpServletRequestBuilder register(String userId, byte[] card) {
        return register(userId, card, userId.toLowerCase(java.util.Locale.ROOT) + "@example.com");
    }

    static MockMultipartHttpServletRequestBuilder register(String userId, byte[] card, String email) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                .file(new MockMultipartFile("ssnCard", "my card.png", MediaType.IMAGE_PNG_VALUE, card))
                .param("userId", userId)
                .param("password", PASSWORD)
                .param("email", email)
                .param("mobilePhone", "(206) 555-0100")
                .param("zip", "98101")
                .param("authorize", "true");
    }

    static MockMultipartHttpServletRequestBuilder register(String userId, String ssnOnCard) {
        return register(userId, FakeIdentityVerifier.card(ssnOnCard));
    }

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.MutableClock CLOCK =
                new InMemoryRepositories.MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        static final InMemoryRepositories.Sessions SESSIONS = new InMemoryRepositories.Sessions();
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();
        static final FakeIdentityVerifier KYC = new FakeIdentityVerifier();
        static final RecordingOtpSender OTP = new RecordingOtpSender();
        static final InMemoryRepositories.OtpChallenges CHALLENGES = new InMemoryRepositories.OtpChallenges();

        @Bean
        AuthService authService() {
            return new AuthService(
                    USERS, SESSIONS, new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")), KYC,
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), CLOCK),
                    Duration.ofMinutes(5), CLOCK);
        }

        @Bean
        OtpLoginService otpLoginService(AuthService auth) {
            return OtpTesting.service(auth, CHALLENGES, OTP, CLOCK, new com.creditagent.common.AuditLog());
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditScoreService scores;

    @Test
    void fullFlow() throws Exception {
        mvc.perform(register("Avery", "123-45-6789"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("avery"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(PASSWORD))));

        mvc.perform(register("Avery", "123-45-6789"))
                .andExpect(status().isConflict());
        // A card with the same SSN under another user id: also refused.
        mvc.perform(register("imposter", "123-45-6789"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An account already exists for that SSN."));
        // Another SSN, but Avery's email in other letter case: refused, so no one can watch someone else's address.
        mvc.perform(register("copycat", FakeIdentityVerifier.card("234-56-7890"), "AVERY@Example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An account already exists for that email address."));

        String login = mvc.perform(OtpTesting.loginRequest(mvc, Wiring.OTP, "avery", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(300))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.token");

        mvc.perform(get("/api/v1/auth/session").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("avery"))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/session").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("ended")));
    }

    @Test
    void aBlurryCardPhotoIs422WithAReasonAndCanBeRetried() throws Exception {
        mvc.perform(register("dana", FakeIdentityVerifier.blurry()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("IMAGE_BLURRY"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("blurry")));

        mvc.perform(register("dana", "456-78-9012")).andExpect(status().isCreated());
    }

    @Test
    void theZipCodeIsRequiredAndStoredAsFiveDigits() throws Exception {
        MockMultipartHttpServletRequestBuilder noZip = (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                .file(new MockMultipartFile("ssnCard", "card.png", MediaType.IMAGE_PNG_VALUE, FakeIdentityVerifier.card("567-89-0123")))
                .param("userId", "zoe").param("password", PASSWORD).param("email", "zoe@example.com")
                .param("authorize", "true");
        mvc.perform(noZip).andExpect(status().isBadRequest());
        mvc.perform(register("zoe", "567-89-0123").param("zip", "nope")).andExpect(status().isBadRequest());
        mvc.perform(register("yuri", "567-89-0124")).andExpect(status().isCreated());
        assertThat(Wiring.USERS.find("yuri").orElseThrow().zip()).isEqualTo("98101");
    }

    @Test
    void somethingThatIsNotACardIs422() throws Exception {
        mvc.perform(register("erin", "a holiday photo".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("NOT_AN_SSN_CARD"));
    }

    @Test
    void theSsnCannotBeTypedIn() throws Exception {
        // An ssn field is ignored: the account gets the SSN on the card, not the one typed.
        mvc.perform(register("frank", "567-89-0123").param("ssn", "123-45-6789"))
                .andExpect(status().isCreated());
        String login = mvc.perform(OtpTesting.loginRequest(mvc, Wiring.OTP, "frank", PASSWORD))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-0123"))
                .andReturn().getResponse().getContentAsString();
        assertThat(login).doesNotContain("6789");

        // The old JSON form, SSN and all, is no longer accepted.
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"gail\",\"password\":\"" + PASSWORD
                                + "\",\"email\":\"g@example.com\",\"ssn\":\"678-90-1234\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void registrationWithoutTheAuthorisationIsRefusedAndNothingIsStored() throws Exception {
        for (String value : new String[] {null, "false", "on-no"}) {
            MockMultipartHttpServletRequestBuilder form = (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                    .file(new MockMultipartFile("ssnCard", "card.png", MediaType.IMAGE_PNG_VALUE, FakeIdentityVerifier.card("678-90-1236")))
                    .param("userId", "ivy").param("password", PASSWORD).param("email", "ivy@example.com").param("mobilePhone", "2065550100").param("zip", "98101");
            if (value != null) {
                form.param("authorize", value);
            }
            mvc.perform(form).andExpect(status().isBadRequest());
        }
        MockMultipartHttpServletRequestBuilder declined = (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                .file(new MockMultipartFile("ssnCard", "card.png", MediaType.IMAGE_PNG_VALUE, FakeIdentityVerifier.card("678-90-1236")))
                .param("userId", "ivy").param("password", PASSWORD).param("email", "ivy@example.com").param("mobilePhone", "2065550100").param("zip", "98101")
                .param("authorize", "false");
        mvc.perform(declined).andExpect(jsonPath("$.detail").value(AuthController.AUTHORIZATION_REQUIRED));
        assertThat(Wiring.USERS.find("ivy")).isEmpty();
    }

    @Test
    void aPhoneNumberIfSentIsIgnoredAndTheConsentIsRecorded() throws Exception {
        mvc.perform(register("jude", "678-90-1237").param("phone", "+14155550100")).andExpect(status().isCreated());
        User jude = Wiring.USERS.find("jude").orElseThrow();
        assertThat(jude.consentVersion()).isEqualTo(RegistrationPolicy.CONSENT_VERSION);
        assertThat(jude.toString()).doesNotContain("4155550100");
    }

    @Test
    void aMissingCardIs400() throws Exception {
        mvc.perform(multipart("/api/v1/auth/register")
                        .param("userId", "hana").param("password", PASSWORD)
                        .param("email", "h@example.com").param("authorize", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badCredentialsAre401WithAGenericMessage() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ghost\",\"password\":\"whatever-it-is\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_CREDENTIALS));
    }

    @Test
    void weakPasswordIs400WithoutEchoingIt() throws Exception {
        mvc.perform(multipart("/api/v1/auth/register")
                        .file(new MockMultipartFile("ssnCard", "card.png", MediaType.IMAGE_PNG_VALUE,
                                FakeIdentityVerifier.card("234-56-7890")))
                        .param("userId", "blake").param("password", "Password1234")
                        .param("email", "b@example.com").param("authorize", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(Matchers.not(Matchers.containsString("Password1234"))));
    }

    @Test
    void missingOrGarbageTokenIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").header("Authorization", "Basic YTpi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionTimesOutAfterFiveIdleMinutes() throws Exception {
        mvc.perform(register("casey", "345-67-8901")).andExpect(status().isCreated());
        String login = mvc.perform(OtpTesting.loginRequest(mvc, Wiring.OTP, "casey", PASSWORD))
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.token");

        Wiring.CLOCK.advance(Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/auth/session").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("timed out")));
        assertThat(Wiring.SESSIONS.byId.values()).noneMatch(s -> s.userId().equals("casey"));
    }

    @Test
    void percentEncodedPathsDoNotSkipAuthentication() throws Exception {
        // %61 is "a": Spring routes /%61pi/... to /api/..., so the filter must treat it the same.
        for (String path : new String[] {"/%61pi/v1/credit-score", "/api;x=1/v1/credit-score", "/API/v1/credit-score"}) {
            int status = mvc.perform(post(java.net.URI.create(path)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ssn\":\"123-45-6789\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(path).isEqualTo(401);
        }
    }

    @Test
    void scoreApiNeedsAToken() throws Exception {
        given(scores.scoreFor(Ssn.parse("123-45-6789"))).willReturn(CreditScore.of(700));
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserCanLockAndUnlockTheirReport() throws Exception {
        mvc.perform(register("lena", "678-90-1234")).andExpect(status().isCreated());
        String login = mvc.perform(OtpTesting.loginRequest(mvc, Wiring.OTP, "lena", PASSWORD))
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.token");

        mvc.perform(get("/api/v1/credit-report/status").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("UNLOCKED"));
        mvc.perform(put("/api/v1/credit-report/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reportStatus\":\"LOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("LOCKED"));
        mvc.perform(get("/api/v1/credit-report/status").header("Authorization", bearer))
                .andExpect(jsonPath("$.reportStatus").value("LOCKED"));

        // Only the two statuses are accepted.
        mvc.perform(put("/api/v1/credit-report/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reportStatus\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/credit-report/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // Enum ordinals and lower case are refused too, and the status is unchanged.
        for (String bad : new String[] {"0", "1", "\"0\"", "\"locked\""}) {
            mvc.perform(put("/api/v1/credit-report/status").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reportStatus\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/credit-report/status").header("Authorization", bearer))
                .andExpect(jsonPath("$.reportStatus").value("LOCKED"));
    }

    @Test
    void theReportStatusNeedsALogin() throws Exception {
        mvc.perform(get("/api/v1/credit-report/status")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/credit-report/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportStatus\":\"LOCKED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aReportStatusCallForAnAccountThatIsGoneIs401WithTheBearerChallenge() throws Exception {
        // The token and session are still valid, so the filter lets the call through; the
        // controller's own 401 (AuthErrorHandling) must answer it with the same challenge.
        mvc.perform(register("mona", "789-01-2345")).andExpect(status().isCreated());
        String login = mvc.perform(OtpTesting.loginRequest(mvc, Wiring.OTP, "mona", PASSWORD))
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.token");
        Wiring.USERS.byId.remove("mona");

        mvc.perform(get("/api/v1/credit-report/status").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("ended")));
        mvc.perform(put("/api/v1/credit-report/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reportStatus\":\"LOCKED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("ended")));
    }

    // ---------------------------------------------------------------- one-time sign-in codes

    /** The password step for {@code userId}; returns the challenge id. */
    String passwordStep(String userId) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.channels[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.channels[0].destination").value(userId.charAt(0) + "***@example.com"))
                .andExpect(jsonPath("$.channels[1].channel").value("SMS"))
                .andExpect(jsonPath("$.channels[1].destination").value("***-***-0100"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("2065550100").doesNotContain(userId + "@");
        return JsonPath.read(body, "$.challengeId");
    }

    org.springframework.test.web.servlet.ResultActions send(String challengeId, String channel) throws Exception {
        return mvc.perform(post("/api/v1/auth/login/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"" + challengeId + "\",\"channel\":\"" + channel + "\"}"));
    }

    org.springframework.test.web.servlet.ResultActions verifyCode(String challengeId, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/login/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}"));
    }

    @Test
    void thePasswordAloneGivesNoTokenAndTheTextedCodeDoes() throws Exception {
        mvc.perform(register("olive", "890-12-3456")).andExpect(status().isCreated());
        assertThat(Wiring.USERS.find("olive").orElseThrow().mobilePhone()).isEqualTo("+12065550100");
        String challenge = passwordStep("olive");

        // No token: the score API is still closed.
        send(challenge, "sms")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.channel").value("SMS"))
                .andExpect(jsonPath("$.destination").value("***-***-0100"))
                .andExpect(jsonPath("$.codeExpiresInSeconds").value(300))
                .andExpect(jsonPath("$.resendAfterSeconds").value(30));
        assertThat(Wiring.OTP.last().destination()).isEqualTo("+12065550100");

        String login = verifyCode(challenge, Wiring.OTP.lastCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value("olive"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer " + JsonPath.read(login, "$.token")))
                .andExpect(status().isOk());
        // The code works once.
        verifyCode(challenge, Wiring.OTP.lastCode())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.restart").value(true));
    }

    @Test
    void wrongCodesSayHowManyTriesAreLeft() throws Exception {
        mvc.perform(register("piper", "456-12-3456")).andExpect(status().isCreated());
        String challenge = passwordStep("piper");
        send(challenge, "EMAIL").andExpect(status().isAccepted());
        String wrong = Wiring.OTP.lastCode().equals("000000") ? "111111" : "000000";

        verifyCode(challenge, wrong)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value(OtpLoginService.WRONG_CODE))
                .andExpect(jsonPath("$.restart").value(false))
                .andExpect(jsonPath("$.attemptsLeft").value(4))
                .andExpect(jsonPath("$.token").doesNotExist());
        verifyCode(challenge, "12ab")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter the 6-digit code."));
    }

    @Test
    void resendsTooSoonGet429WithRetryAfter() throws Exception {
        mvc.perform(register("quinn", "012-34-5678")).andExpect(status().isCreated());
        String challenge = passwordStep("quinn");
        send(challenge, "EMAIL").andExpect(status().isAccepted());

        send(challenge, "SMS")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30))
                .andExpect(jsonPath("$.restart").value(false));
    }

    @Test
    void badChannelsUnknownChallengesAndProviderOutagesAreReported() throws Exception {
        mvc.perform(register("rhea", "123-45-6780")).andExpect(status().isCreated());
        String challenge = passwordStep("rhea");

        send(challenge, "carrier-pigeon")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose email or text message."));
        send("not-a-challenge", "EMAIL")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.restart").value(true));
        verifyCode(challenge, "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose where to send your code first."));
        mvc.perform(post("/api/v1/auth/login/otp").contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest());

        Wiring.OTP.failure = OtpLoginServiceTest.rejected(com.creditagent.web.auth.otp.OtpChannel.EMAIL);
        try {
            send(challenge, "EMAIL")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("could not send your code")))
                    .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist())
                    .andExpect(header().doesNotExist("Retry-After"));
            // Refused, so given back: the other channel works at once. Then a failure that may have
            // delivered counts, and says when to ask again.
            Wiring.OTP.failure = OtpLoginServiceTest.uncertain(com.creditagent.web.auth.otp.OtpChannel.SMS);
            send(challenge, "SMS")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("could not confirm")))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(30))
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(content().string(Matchers.not(Matchers.containsString("5000"))));
        } finally {
            Wiring.OTP.failure = null;
        }
    }

    @Test
    void registrationNeedsAValidMobileNumber() throws Exception {
        mvc.perform(register("sage", "234-56-7801").param("mobilePhone", ""))
                .andExpect(status().isBadRequest());
        mvc.perform(register("sage", "234-56-7801").param("mobilePhone", "555-0100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("mobile number")));
        MockMultipartHttpServletRequestBuilder noPhone = (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                .file(new MockMultipartFile("ssnCard", "card.png", MediaType.IMAGE_PNG_VALUE, FakeIdentityVerifier.card("234-56-7801")))
                .param("userId", "sage").param("password", PASSWORD).param("email", "sage@example.com")
                .param("zip", "98101").param("authorize", "true");
        mvc.perform(noPhone).andExpect(status().isBadRequest());
        assertThat(Wiring.USERS.find("sage")).isEmpty();
    }
}
