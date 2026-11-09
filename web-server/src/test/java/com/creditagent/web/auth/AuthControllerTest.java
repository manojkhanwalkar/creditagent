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
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                .file(new MockMultipartFile("ssnCard", "my card.png", MediaType.IMAGE_PNG_VALUE, card))
                .param("userId", userId)
                .param("password", PASSWORD)
                .param("email", "avery@example.com")
                .param("phone", "+1 415 555 0100");
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
        static final FakeIdentityVerifier KYC = new FakeIdentityVerifier();

        @Bean
        AuthService authService() {
            return new AuthService(
                    new InMemoryRepositories.Users(), SESSIONS, new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")), KYC,
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), CLOCK),
                    Duration.ofMinutes(5), CLOCK);
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

        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"avery\",\"password\":\"" + PASSWORD + "\"}"))
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
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"frank\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-0123"))
                .andReturn().getResponse().getContentAsString();
        assertThat(login).doesNotContain("6789");

        // The old JSON form, SSN and all, is no longer accepted.
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"gail\",\"password\":\"" + PASSWORD
                                + "\",\"email\":\"g@example.com\",\"phone\":\"+14155550101\",\"ssn\":\"678-90-1234\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aMissingCardIs400() throws Exception {
        mvc.perform(multipart("/api/v1/auth/register")
                        .param("userId", "hana").param("password", PASSWORD)
                        .param("email", "h@example.com").param("phone", "+14155550101"))
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
                        .param("email", "b@example.com").param("phone", "+14155550101"))
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
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"casey\",\"password\":\"" + PASSWORD + "\"}"))
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
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"lena\",\"password\":\"" + PASSWORD + "\"}"))
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
    }

    @Test
    void theReportStatusNeedsALogin() throws Exception {
        mvc.perform(get("/api/v1/credit-report/status")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/credit-report/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportStatus\":\"LOCKED\"}"))
                .andExpect(status().isUnauthorized());
    }
}
