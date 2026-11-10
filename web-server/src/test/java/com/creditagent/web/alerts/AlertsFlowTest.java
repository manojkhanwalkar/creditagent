package com.creditagent.web.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.clients.ServiceUnavailableException;
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
import java.util.List;
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

/** Dark-web alerts at login and on GET /api/v1/alerts, through the real filters and login endpoint. */
@WebMvcTest(controllers = AlertsController.class)
@Import({AlertsFlowTest.Wiring.class, com.creditagent.web.auth.AuthControllerConfig.class})
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class AlertsFlowTest {

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
        AlertsService alertsService(AuthService auth, AlertsClient client) {
            return new AlertsService(auth, client, client);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthService auth;

    @MockitoBean
    AlertsClient client;

    @MockitoBean
    com.creditagent.web.CreditScoreService scores;

    @BeforeEach
    void registerUsers() {
        if (Wiring.USERS.find("ada").isEmpty()) {
            auth.register("ada", PASSWORD, "Ada@Example.com", "+1 415 555 0100", "98101", FakeIdentityVerifier.card("123-45-6789"));
            auth.register("bob", PASSWORD, "bob@example.com", "+1 415 555 0100", "98101", FakeIdentityVerifier.card("234-56-7890"));
        }
    }

    String login(String user) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anExposedUserIsWarnedAtLoginAndAdvisedToBuyIdentityProtection() throws Exception {
        given(client.lookup("Ada@Example.com")).willReturn(new AlertsClient.Lookup(true,
                List.of(new AlertsClient.Finding("breach-forum-beta", Instant.parse("2026-11-09T12:00:00Z")))));
        String body = login("ada");
        assertThat((Boolean) JsonPath.read(body, "$.alerts.exposed")).isTrue();
        assertThat((String) JsonPath.read(body, "$.alerts.message")).contains("dark web").contains("identity protection");
        assertThat((String) JsonPath.read(body, "$.alerts.findings[0].location")).isEqualTo("breach-forum-beta");

        String bearer = "Bearer " + JsonPath.read(body, "$.token");
        mvc.perform(get(AlertsController.PATH).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposed").value(true));
    }

    @Test
    void aUserWhoWasNotFoundSeesNoAlert() throws Exception {
        given(client.lookup("bob@example.com")).willReturn(new AlertsClient.Lookup(false, List.of()));
        String body = login("bob");
        assertThat((Boolean) JsonPath.read(body, "$.alerts.exposed")).isFalse();
        assertThat((Object) JsonPath.read(body, "$.alerts.message")).isNull();
    }

    @Test
    void anAlertsOutageNeverFailsTheLogin() throws Exception {
        given(client.lookup(anyString())).willThrow(new ServiceUnavailableException("down", null));
        String body = login("bob");
        assertThat((String) JsonPath.read(body, "$.alerts.status")).isEqualTo("UNAVAILABLE");
        assertThat((String) JsonPath.read(body, "$.token")).isNotBlank();
    }

    @Test
    void alertsNeedALogin() throws Exception {
        mvc.perform(get(AlertsController.PATH)).andExpect(status().isUnauthorized());
        verify(client, never()).lookup(anyString());
    }
}
