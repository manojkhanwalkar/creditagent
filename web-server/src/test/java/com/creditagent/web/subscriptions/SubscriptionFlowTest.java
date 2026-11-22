package com.creditagent.web.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.AuditLog;
import com.creditagent.web.WebServerProperties;
import com.creditagent.web.auth.AuthControllerConfig;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.FakeIdentityVerifier;
import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.JwtService;
import com.creditagent.web.auth.OtpTesting;
import com.creditagent.web.auth.SsnHasher;
import com.creditagent.web.auth.TestSecrets;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** ID Protection at registration, the offer at login, and the subscriptions API, through the real filters. */
@WebMvcTest(controllers = SubscriptionController.class)
@Import({SubscriptionFlowTest.Wiring.class, AuthControllerConfig.class})
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class SubscriptionFlowTest {

    static final String PASSWORD = "correct horse battery staple";

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();
        static final InMemorySubscriptions STORE = new InMemorySubscriptions();

        @Bean
        AuthService authService() {
            return new AuthService(USERS, new InMemoryRepositories.Sessions(), new BCryptPasswordEncoder(4),
                    new SsnHasher(TestSecrets.of("ssn")), new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), Clock.systemUTC()),
                    Duration.ofMinutes(5), Clock.systemUTC());
        }

        @Bean
        SubscriptionService subscriptionService(AuthService auth) {
            return new SubscriptionService(auth, STORE, PaymentGateway.test(),
                    new SubscriptionProperties(null, null, null, 1999L), new AuditLog(), Clock.systemUTC());
        }
    }

    @Autowired
    MockMvc mvc;

    static MockMultipartHttpServletRequestBuilder register(String userId, String ssn) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/auth/register")
                .file(new MockMultipartFile("ssnCard", "card.png", MediaType.IMAGE_PNG_VALUE, FakeIdentityVerifier.card(ssn)))
                .param("userId", userId).param("password", PASSWORD).param("email", userId + "@example.com")
                .param("mobilePhone", "(206) 555-0100").param("zip", "98101").param("authorize", "true");
    }

    String login(String user) throws Exception {
        return mvc.perform(OtpTesting.loginRequest(mvc, AuthControllerConfig.OTP, user, PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void idProtectionTickedAtRegistrationStartsATermAndLoginShowsItWithNoOffer() throws Exception {
        mvc.perform(register("ivy", "345-67-8901").param("idProtection", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idProtection.outcome").value("ACTIVE"));
        assertThat(Wiring.STORE.activeForEmail("ivy@example.com", com.creditagent.subscriptions.Product.ID_PROTECTION,
                java.time.Instant.now())).isTrue();

        String body = login("ivy");
        assertThat((String) JsonPath.read(body, "$.subscriptions.products[0].product")).isEqualTo("ID_PROTECTION");
        assertThat((Boolean) JsonPath.read(body, "$.subscriptions.products[0].active")).isTrue();
        assertThat((List<?>) JsonPath.read(body, "$.subscriptions.upsell")).isEmpty();
    }

    @Test
    void aFreeUserIsOfferedIdProtectionAtLoginAndCanBuyItOnce() throws Exception {
        mvc.perform(register("finn", "456-78-9012"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idProtection").doesNotExist());

        String body = login("finn");
        assertThat((List<String>) JsonPath.read(body, "$.subscriptions.upsell")).containsExactly("ID_PROTECTION");
        assertThat((Integer) JsonPath.read(body, "$.subscriptions.products[0].priceCents")).isEqualTo(1999);
        assertThat((Boolean) JsonPath.read(body, "$.subscriptions.paymentsAvailable")).isTrue();
        String bearer = "Bearer " + JsonPath.read(body, "$.token");

        mvc.perform(post(SubscriptionController.PATH).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"id_protection\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(post(SubscriptionController.PATH).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"ID_PROTECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_ACTIVE"));
        mvc.perform(get(SubscriptionController.PATH).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].active").value(true))
                .andExpect(jsonPath("$.products[1].product").value("CREDIT_MONITORING"))
                .andExpect(jsonPath("$.products[1].offered").value(false));

        mvc.perform(post(SubscriptionController.PATH).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"CREDIT_MONITORING\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(SubscriptionController.PATH).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"gold\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePlansArePublicAndHoldNoUserData() throws Exception {
        mvc.perform(get(SubscriptionController.PLANS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsAvailable").value(true))
                .andExpect(jsonPath("$.plans[0].product").value("ID_PROTECTION"))
                .andExpect(jsonPath("$.plans[0].priceCents").value(1999))
                .andExpect(jsonPath("$.plans[0].currency").value("USD"))
                .andExpect(jsonPath("$.plans.length()").value(1));
    }

    @Test
    void subscriptionsNeedALogin() throws Exception {
        mvc.perform(get(SubscriptionController.PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(SubscriptionController.PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"ID_PROTECTION\"}")).andExpect(status().isUnauthorized());
    }
}
