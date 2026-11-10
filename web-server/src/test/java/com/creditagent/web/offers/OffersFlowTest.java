package com.creditagent.web.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.Ssn;
import com.creditagent.web.WebServerProperties;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.FakeIdentityVerifier;
import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.JwtService;
import com.creditagent.web.auth.SsnHasher;
import com.creditagent.web.auth.TestSecrets;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Offers at login and on GET /api/v1/offers, through the real filters and the real login endpoint. */
@WebMvcTest(controllers = OffersController.class)
@Import({OffersFlowTest.Wiring.class, com.creditagent.web.auth.AuthControllerConfig.class})
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class OffersFlowTest {

    static final String PASSWORD = "correct horse battery staple";

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.MutableClock CLOCK =
                new InMemoryRepositories.MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();

        @Bean
        AuthService authService() {
            return new AuthService(USERS, new InMemoryRepositories.Sessions(), new BCryptPasswordEncoder(4),
                    new SsnHasher(TestSecrets.of("ssn")), new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), CLOCK),
                    Duration.ofMinutes(5), CLOCK);
        }

        @Bean
        OffersService offersService(AuthService auth, OffersClient client) {
            return new OffersService(auth, client);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthService auth;

    @MockitoBean
    OffersClient client;

    @MockitoBean
    com.creditagent.web.CreditScoreService scores;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void registerUsers() {
        if (Wiring.USERS.find("ada").isEmpty()) {
            auth.register("ada", PASSWORD, "ada@example.com", "+1 415 555 0100", "98101-1234", FakeIdentityVerifier.card("123-45-6789"));
            auth.register("bob", PASSWORD, "bob@example.com", "+1 415 555 0100", FakeIdentityVerifier.card("234-56-7890"));
            auth.register("eve", PASSWORD, "eve@example.com", "+1 415 555 0100", "02134", FakeIdentityVerifier.card("567-89-0123"));
        }
    }

    JsonNode offers(int count) {
        StringBuilder list = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            list.append(i > 1 ? "," : "").append("{\"offerId\":\"o").append(i).append("\",\"rank\":").append(i).append('}');
        }
        return json.readTree("{\"offers\":" + list + "],\"disclaimer\":\"Not firm offers.\"}");
    }

    String login(String user) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void withoutARecordedScoreLoginSaysSoAndCallsNothing() throws Exception {
        String body = login("eve");   // never scored
        assertThat((String) JsonPath.read(body, "$.offers.status")).isEqualTo("NO_SCORE");
        verify(client, never()).offers(anyInt(), any());
    }

    @Test
    void loginReturnsUpToTenOffersForTheRecordedScoreAndZip() throws Exception {
        auth.recordScore("ada", 731);
        given(client.offers(731, "98101")).willReturn(offers(12));
        String body = login("ada");
        assertThat((String) JsonPath.read(body, "$.offers.status")).isEqualTo("OK");
        assertThat((Integer) JsonPath.read(body, "$.offers.score")).isEqualTo(731);
        assertThat((String) JsonPath.read(body, "$.offers.zip")).isEqualTo("98101");
        assertThat((Integer) JsonPath.read(body, "$.offers.offers.length()")).isEqualTo(10);
        assertThat((String) JsonPath.read(body, "$.offers.disclaimer")).isEqualTo("Not firm offers.");

        String bearer = "Bearer " + JsonPath.read(body, "$.token");
        mvc.perform(get(OffersController.PATH).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.offers[0].offerId").value("o1"));
    }

    @Test
    void anOffersOutageNeverFailsTheLogin() throws Exception {
        auth.recordScore("ada", 700);
        given(client.offers(700, "98101")).willThrow(new ServiceUnavailableException("down", null));
        String body = login("ada");
        assertThat((String) JsonPath.read(body, "$.offers.status")).isEqualTo("UNAVAILABLE");
        assertThat((String) JsonPath.read(body, "$.token")).isNotBlank();
    }

    @Test
    void offersNeedALogin() throws Exception {
        mvc.perform(get(OffersController.PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void anAccountWithoutAZipGetsNationwideOffers() throws Exception {
        auth.recordScore("bob", 650);
        given(client.offers(650, null)).willReturn(offers(3));
        assertThat((Integer) JsonPath.read(login("bob"), "$.offers.offers.length()")).isEqualTo(3);
    }

    @Test
    void theFirstScoreIsRecordedInTheBackgroundAtRegistration() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        given(scores.scoreFor(Ssn.parse("345-67-8901"))).willReturn(com.creditagent.common.CreditScore.of(688));
        auth.addRegistrationListener(new ScoreRecorder(auth, scores, queued::add));
        auth.register("cyd", PASSWORD, "cyd@example.com", "+1 415 555 0100", "10001", FakeIdentityVerifier.card("345-67-8901"));
        assertThat(Wiring.USERS.find("cyd").orElseThrow().lastScore()).isNull();   // not on the request thread
        queued.forEach(Runnable::run);
        assertThat(Wiring.USERS.find("cyd").orElseThrow().lastScore()).isEqualTo(688);

        // A failure (no report yet, a service down) is swallowed: registration already succeeded.
        given(scores.scoreFor(Ssn.parse("456-78-9012"))).willThrow(new ServiceUnavailableException("down", null));
        queued.clear();
        auth.register("dia", PASSWORD, "dia@example.com", "+1 415 555 0100", "10001", FakeIdentityVerifier.card("456-78-9012"));
        queued.forEach(Runnable::run);
        assertThat(Wiring.USERS.find("dia").orElseThrow().lastScore()).isNull();
        // An unknown user is ignored.
        auth.recordScore("nobody", 700);
    }
}
