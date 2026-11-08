package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Register, log in, use the token on the score API, log out: through the real filter chain. */
@WebMvcTest(controllers = AuthController.class)
@Import(AuthControllerTest.Wiring.class)
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class AuthControllerTest {

    static final String PASSWORD = "correct horse battery staple";
    static final String REGISTER = """
            {"userId":"Avery","password":"%s","email":"avery@example.com","phone":"+1 415 555 0100","ssn":"123-45-6789"}
            """.formatted(PASSWORD);

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.MutableClock CLOCK =
                new InMemoryRepositories.MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        static final InMemoryRepositories.Sessions SESSIONS = new InMemoryRepositories.Sessions();

        @Bean
        AuthService authService() {
            return new AuthService(
                    new InMemoryRepositories.Users(), SESSIONS, new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")),
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
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("avery"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(PASSWORD))));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isConflict());
        // Same SSN under another user id: also refused.
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER.replace("Avery", "imposter")))
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
    void badCredentialsAre401WithAGenericMessage() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ghost\",\"password\":\"whatever-it-is\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_CREDENTIALS));
    }

    @Test
    void weakPasswordIs400WithoutEchoingIt() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"blake\",\"password\":\"Password1234\",\"email\":\"b@example.com\",\"phone\":\"+14155550101\",\"ssn\":\"234-56-7890\"}"))
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
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER.replace("Avery", "casey").replace("123-45-6789", "345-67-8901")));
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
    void scoreApiNeedsAToken() throws Exception {
        given(scores.scoreFor(Ssn.parse("123-45-6789"))).willReturn(CreditScore.of(700));
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isUnauthorized());
    }
}
