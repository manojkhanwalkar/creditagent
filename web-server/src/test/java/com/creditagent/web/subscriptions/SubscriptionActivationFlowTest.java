package com.creditagent.web.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.AuditLog;
import com.creditagent.subscriptions.Product;
import com.creditagent.subscriptions.Subscription;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * {@code POST /internal/v1/subscriptions/activations} through the real filters: the service token
 * guards it, a user's login cannot reach it, every outcome maps to its status, and the published
 * OpenAPI contract matches the controller.
 */
@WebMvcTest(controllers = {SubscriptionActivationController.class, SubscriptionController.class})
@Import({SubscriptionActivationFlowTest.Wiring.class, AuthControllerConfig.class})
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000",
    "credit.internal.token=" + SubscriptionActivationFlowTest.TOKEN
})
class SubscriptionActivationFlowTest {

    static final String TOKEN = "internal-token-for-activation-tests-0123456789";
    static final String SERVICE = "Bearer " + TOKEN;
    static final Path SPEC = Path.of("..", "docs", "api", "subscriptions-internal-v1.yaml");
    static final String PASSWORD = "correct horse battery staple";

    /** The Subscriptions table in memory, which can be told to fail like DynamoDB. */
    static class FlakySubscriptions extends InMemorySubscriptions {
        final AtomicBoolean down = new AtomicBoolean();

        @Override
        public synchronized boolean activate(Subscription subscription, String email) {
            if (down.get()) {
                throw SdkClientException.create("DynamoDB down");
            }
            return super.activate(subscription, email);
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final FlakySubscriptions STORE = new FlakySubscriptions();

        @Bean
        AuthService authService() {
            return new AuthService(new InMemoryRepositories.Users(), new InMemoryRepositories.Sessions(),
                    new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")), new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), Clock.systemUTC()),
                    Duration.ofMinutes(5), Clock.systemUTC());
        }

        @Bean
        SubscriptionService subscriptionService(AuthService auth) {
            return new SubscriptionService(auth, STORE, PaymentGateway.none(),
                    new SubscriptionProperties(null, null, null, null), new AuditLog(), Clock.systemUTC());
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    static int users;

    @BeforeEach
    void storeUp() {
        Wiring.STORE.down.set(false);
    }

    /** Registers a fresh user (no subscription) and answers its id. */
    String newUser() throws Exception {
        String userId = "pay" + (++users) + "user";
        String ssn = String.format("%03d-45-%04d", 600 + users, 1000 + users);
        mvc.perform(SubscriptionFlowTest.register(userId, ssn)).andExpect(status().isCreated());
        return userId;
    }

    static String body(String userId, String product, String paymentReference) {
        return "{\"userId\":\"" + userId + "\",\"product\":\"" + product + "\",\"paymentReference\":\"" + paymentReference + "\"}";
    }

    static MockHttpServletRequestBuilder activation(String bearer, String body) {
        MockHttpServletRequestBuilder request = post(SubscriptionActivationController.PATH)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return bearer == null ? request : request.header("Authorization", bearer);
    }

    @Test
    void aPaymentStartsATermOnceAndAnotherPaymentIsRefusedForARefund() throws Exception {
        String user = newUser();
        String expiresAt = JsonPath.read(mvc.perform(activation(SERVICE, body(user, "ID_PROTECTION", "pi_1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString(), "$.expiresAt");

        mvc.perform(activation(SERVICE, body(user, "id_protection", "pi_1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_SAME_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt));

        mvc.perform(activation(SERVICE, body(user, "ID_PROTECTION", "pi_2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
        assertThat(Wiring.STORE.findAll(user)).singleElement().extracting(Subscription::paymentReference).isEqualTo("pi_1");
    }

    @Test
    void unknownUsersAndInvalidRequestsAreRefused() throws Exception {
        mvc.perform(activation(SERVICE, body("nobody", "ID_PROTECTION", "pi_1"))).andExpect(status().isNotFound());
        String user = newUser();
        for (String bad : List.of(body(user, "CREDIT_MONITORING", "pi_1"), body(user, "gold", "pi_1"),
                body("Bad User", "ID_PROTECTION", "pi_1"), body(user, "ID_PROTECTION", "has space"),
                body(user, "ID_PROTECTION", "p".repeat(129)), body("u".repeat(65), "ID_PROTECTION", "pi_1"),
                body(user, "ID_PROTECTION", ""), "{\"userId\":\"" + user + "\",\"product\":\"ID_PROTECTION\"}", "{", "")) {
            mvc.perform(activation(SERVICE, bad)).andExpect(status().isBadRequest());
        }
        assertThat(Wiring.STORE.findAll(user)).isEmpty();
    }

    @Test
    void aStoreFailureAnswers503WithRetryAfter() throws Exception {
        String user = newUser();
        Wiring.STORE.down.set(true);
        mvc.perform(activation(SERVICE, body(user, "ID_PROTECTION", "pi_9")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
        Wiring.STORE.down.set(false);
        mvc.perform(activation(SERVICE, body(user, "ID_PROTECTION", "pi_9"))).andExpect(status().isOk());
    }

    @Test
    void onlyTheServiceTokenOpensItAndNeverThroughThePublicApi() throws Exception {
        String user = newUser();
        String login = mvc.perform(OtpTesting.loginRequest(mvc, AuthControllerConfig.OTP, user, PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String userBearer = "Bearer " + JsonPath.read(login, "$.token");
        String body = body(user, "ID_PROTECTION", "pi_x");

        mvc.perform(activation(null, body)).andExpect(status().isUnauthorized());
        mvc.perform(activation("Bearer wrong", body)).andExpect(status().isUnauthorized());
        mvc.perform(activation(userBearer, body)).andExpect(status().isUnauthorized());

        // The public API has no such route, and the service token is not a user's login there.
        for (String bearer : new String[] {SERVICE, userBearer}) {
            int answer = mvc.perform(post("/api/v1/subscriptions/activations").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            assertThat(answer).as(bearer.equals(SERVICE) ? "service token" : "user token").isBetween(400, 499);
        }
        mvc.perform(post("/api/v1/subscriptions/activations").header("Authorization", SERVICE)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(Wiring.STORE.findAll(user)).isEmpty();
    }

    // --- The published contract (docs/api/subscriptions-internal-v1.yaml) matches the controller ---

    @SuppressWarnings("unchecked")
    static Map<String, Object> spec() throws Exception {
        try (InputStream in = Files.newInputStream(SPEC)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> at(Map<String, Object> node, String... path) {
        Map<String, Object> current = node;
        for (String step : path) {
            current = (Map<String, Object>) current.get(step);
            assertThat(current).as(String.join(".", path)).isNotNull();
        }
        return current;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSpecDocumentsExactlyTheControllersRouteAndNames() throws Exception {
        Map<String, Object> spec = spec();
        assertThat(spec.get("openapi")).asString().startsWith("3.");
        Set<String> documented = new TreeSet<>();
        at(spec, "paths").forEach((path, operations) -> ((Map<String, Object>) operations).keySet()
                .forEach(method -> documented.add(method.toUpperCase() + " " + path)));
        Set<String> served = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream().flatMap(pattern -> info.getMethodsCondition().getMethods()
                        .stream().map(method -> method + " " + pattern)))
                .filter(route -> route.contains(" /internal/v1/subscriptions"))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(served).isEqualTo(documented).containsExactly("POST " + SubscriptionActivationController.PATH);

        Map<String, Object> request = at(spec, "components", "schemas", "ActivationRequest", "properties");
        assertThat((List<String>) at(request, "product").get("enum")).containsExactlyInAnyOrderElementsOf(
                SubscriptionService.OFFERED.stream().map(Product::name).toList());
        assertThat(at(request, "userId").get("maxLength")).isEqualTo(64);
        assertThat(at(request, "paymentReference").get("maxLength")).isEqualTo(128);
        assertThat((List<String>) at(spec, "components", "schemas", "ActivationResponse", "properties", "outcome").get("enum"))
                .containsExactlyElementsOf(Arrays.stream(SubscriptionService.ActivationOutcome.values()).map(Enum::name).toList());
    }

    @Test
    void everyStatusTheEndpointAnswersIsDocumentedAndEveryDocumentedStatusHappens() throws Exception {
        Set<String> documented = new TreeSet<>(
                at(spec(), "paths", SubscriptionActivationController.PATH, "post", "responses").keySet());
        Set<String> produced = new TreeSet<>();
        String user = newUser();
        produced.add(code(mvc.perform(activation(SERVICE, body(user, "ID_PROTECTION", "pi_a")))));       // 200
        produced.add(code(mvc.perform(activation(SERVICE, body(user, "ID_PROTECTION", "pi_b")))));       // 409
        produced.add(code(mvc.perform(activation(SERVICE, body("ghost", "ID_PROTECTION", "pi_a")))));    // 404
        produced.add(code(mvc.perform(activation(SERVICE, body(user, "gold", "pi_a")))));                // 400
        produced.add(code(mvc.perform(activation(null, body(user, "ID_PROTECTION", "pi_a")))));          // 401
        String other = newUser();
        Wiring.STORE.down.set(true);
        produced.add(code(mvc.perform(activation(SERVICE, body(other, "ID_PROTECTION", "pi_c")))));      // 503
        assertThat(produced).isEqualTo(documented);
    }

    private static String code(ResultActions result) {
        return Integer.toString(result.andReturn().getResponse().getStatus());
    }
}
