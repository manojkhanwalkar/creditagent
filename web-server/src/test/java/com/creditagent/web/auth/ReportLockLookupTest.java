package com.creditagent.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.Ssn;
import com.creditagent.web.CreditScoreService;
import com.creditagent.web.WebServerProperties;
import java.time.Duration;
import java.time.Instant;
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

/** The internal lock check, through the real filter chain (token filter included). */
@WebMvcTest(controllers = ReportLockLookupController.class)
@Import(ReportLockLookupTest.Wiring.class)
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000",
    "credit.internal.token=" + ReportLockLookupTest.TOKEN
})
class ReportLockLookupTest {

    static final String TOKEN = "internal-test-token-internal-test-token";

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();
        static final SsnHasher HASHER = new SsnHasher(TestSecrets.of("ssn"));

        @Bean
        AuthService authService() {
            InMemoryRepositories.MutableClock clock = new InMemoryRepositories.MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
            return new AuthService(USERS, new InMemoryRepositories.Sessions(), new BCryptPasswordEncoder(4), HASHER,
                    new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock),
                    Duration.ofMinutes(5), clock);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditScoreService scores;

    static void store(String userId, String ssn, ReportStatus status) {
        Wiring.USERS.byId.put(userId, new User(userId, "$2a$hash", userId + "@example.com", "+14155550100",
                Wiring.HASHER.hash(Ssn.parse(ssn)), ssn.substring(ssn.length() - 4), "kyc_" + userId,
                Instant.parse("2026-11-08T12:00:00Z"), status));
    }

    org.springframework.test.web.servlet.ResultActions lookup(String ssn, String token) throws Exception {
        return mvc.perform(post("/internal/v1/report-locks/lookup")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ssn\":\"" + ssn + "\"}"));
    }

    @Test
    void answersWhetherTheReportIsLocked() throws Exception {
        store("lou", "345-67-8902", ReportStatus.LOCKED);
        store("una", "456-78-9013", ReportStatus.UNLOCKED);

        lookup("345-67-8902", TOKEN).andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true));
        lookup("456789013", TOKEN).andExpect(jsonPath("$.locked").value(false));
        // Unregistered: the same answer as unlocked, so the endpoint reveals nothing about accounts.
        lookup("567-89-0124", TOKEN).andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void needsTheInternalTokenNotAUserToken() throws Exception {
        lookup("345-67-8902", "wrong-token").andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/report-locks/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ssn\":\"345-67-8902\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidSsnIs400WithoutEchoingIt() throws Exception {
        lookup("000-12-3456", TOKEN)
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("3456"))));
    }
}
