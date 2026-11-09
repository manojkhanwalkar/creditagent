package com.creditagent.web.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.web.WebServerProperties;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.FakeIdentityVerifier;
import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.JwtService;
import com.creditagent.web.auth.SsnHasher;
import com.creditagent.web.auth.TestSecrets;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole connector OAuth flow through the real filter chain: registration, the sign-in form,
 * the code, the token, the connector's internal calls, revocation, and the ways each step refuses.
 */
@WebMvcTest(controllers = {OAuthController.class, ConnectorApiController.class})
@Import(OAuthFlowTest.Wiring.class)
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000",
    "credit.internal.token=" + OAuthFlowTest.SERVICE_TOKEN
})
class OAuthFlowTest {

    static final String SERVICE_TOKEN = "service-token-service-token-service-token";
    static final String ISSUER = "https://credit.example";
    static final String RESOURCE = "https://connector.example/mcp";
    static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    static final String PASSWORD = "correct horse battery staple";
    static final String SSN = "123-45-6789";
    static final String VERIFIER = "verifier-" + "x".repeat(40);

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.MutableClock CLOCK =
                new InMemoryRepositories.MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();
        static final InMemoryRepositories.Sessions SESSIONS = new InMemoryRepositories.Sessions();

        @Bean
        AuthService authService() {
            return new AuthService(
                    USERS, SESSIONS, new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")),
                    new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), CLOCK),
                    Duration.ofMinutes(5), CLOCK);
        }

        @Bean
        OAuthService oauthService(AuthService auth) {
            OAuthProperties properties = new OAuthProperties(
                    TestSecrets.of("oauth"), ISSUER + "/", RESOURCE, null, true, Duration.ofHours(1), Duration.ofSeconds(60));
            return new OAuthService(properties, auth, CLOCK);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthService auth;

    @MockitoBean
    com.creditagent.web.CreditScoreService scores;

    @BeforeEach
    void registerAvery() {
        if (Wiring.USERS.find("avery").isEmpty()) {
            auth.register("avery", PASSWORD, "avery@example.com", "+1 415 555 0100", FakeIdentityVerifier.card(SSN));
        }
    }

    static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    String registerClient(String name, String... redirectUris) throws Exception {
        String uris = String.join(",", List.of(redirectUris).stream().map(u -> "\"" + u + "\"").toList());
        String body = mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[" + uris + "],\"client_name\":\"" + name + "\","
                                + "\"token_endpoint_auth_method\":\"client_secret_basic\",\"extra\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
                .andExpect(jsonPath("$.grant_types[0]").value("authorization_code"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.client_id");
    }

    Map<String, String> authorizeParams(String clientId) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", CALLBACK);
        params.put("code_challenge", challenge(VERIFIER));
        params.put("code_challenge_method", "S256");
        params.put("state", "state-1");
        params.put("resource", RESOURCE);
        params.put("scope", "credit");
        return params;
    }

    static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder request, Map<String, String> params) {
        params.forEach(request::param);
        return request;
    }

    MockHttpServletRequestBuilder signIn(Map<String, String> params, String password, String ssn) {
        Map<String, String> form = new LinkedHashMap<>(params);
        form.put("userId", "Avery");
        form.put("password", password);
        form.put("ssn", ssn);
        form.put("decision", "allow");
        return withParams(post("/oauth/authorize").contentType(MediaType.APPLICATION_FORM_URLENCODED), form);
    }

    static Map<String, String> query(String location) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return query;
    }

    String code(String clientId) throws Exception {
        String location = mvc.perform(signIn(authorizeParams(clientId), PASSWORD, SSN))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        return query(location).get("code");
    }

    MockHttpServletRequestBuilder tokenRequest(String clientId, String code, String verifier) {
        return post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", CALLBACK)
                .param("code_verifier", verifier)
                .param("resource", RESOURCE + "/");
    }

    String accessToken(String clientId) throws Exception {
        String body = mvc.perform(tokenRequest(clientId, code(clientId), VERIFIER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request, String json) {
        return request.header("Authorization", "Bearer " + SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    MockHttpServletRequestBuilder introspect(String token) {
        return internal(post("/internal/v1/connector/introspect"), "{\"token\":\"" + token + "\"}");
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void publishesMetadataForTheIssuer() throws Exception {
        mvc.perform(get(OAuthController.METADATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth/authorize"))
                .andExpect(jsonPath("$.registration_endpoint").value(ISSUER + "/oauth/register"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
    }

    @Test
    void fullFlowFromRegistrationToRevocation() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);

        mvc.perform(withParams(get("/oauth/authorize"), authorizeParams(clientId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("<strong>Claude</strong>")))
                .andExpect(content().string(Matchers.containsString("name=\"state\" value=\"state-1\"")))
                .andExpect(header().string("Content-Security-Policy",
                        Matchers.containsString("form-action 'self' https://claude.ai")))
                .andExpect(header().string("Cache-Control", "no-store"));

        String location = mvc.perform(signIn(authorizeParams(clientId), PASSWORD, SSN))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith(CALLBACK + "?code=");
        Map<String, String> redirect = query(location);
        assertThat(redirect).containsEntry("state", "state-1").containsEntry("iss", ISSUER);
        assertThat(redirect.get("code")).doesNotContain("123456789");

        String body = mvc.perform(tokenRequest(clientId, redirect.get("code"), VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.scope").value("credit"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        mvc.perform(introspect(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.userId").value("avery"))
                .andExpect(jsonPath("$.ssn").value("123456789"))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"))
                .andExpect(jsonPath("$.resource").value(RESOURCE))
                .andExpect(jsonPath("$.scope").value("credit"));

        mvc.perform(internal(post("/internal/v1/connector/report-status"), "{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("UNLOCKED"));
        mvc.perform(internal(put("/internal/v1/connector/report-status"),
                        "{\"token\":\"" + token + "\",\"reportStatus\":\"LOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportStatus").value("LOCKED"));
        assertThat(auth.isReportLocked(com.creditagent.common.Ssn.parse(SSN))).isTrue();
        mvc.perform(internal(put("/internal/v1/connector/report-status"),
                        "{\"token\":\"" + token + "\",\"reportStatus\":\"UNLOCKED\"}"))
                .andExpect(jsonPath("$.reportStatus").value("UNLOCKED"));

        mvc.perform(post("/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token))
                .andExpect(status().isOk());
        mvc.perform(introspect(token)).andExpect(jsonPath("$.active").value(false));
        mvc.perform(internal(post("/internal/v1/connector/report-status"), "{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", Matchers.containsString("invalid_token")));
    }

    @Test
    void theInternalApiNeedsTheServiceToken() throws Exception {
        mvc.perform(post("/internal/v1/connector/introspect").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredGrantIsInactive() throws Exception {
        String token = accessToken(registerClient("Claude", CALLBACK));
        Wiring.CLOCK.advance(Duration.ofMinutes(61));
        try {
            mvc.perform(introspect(token)).andExpect(jsonPath("$.active").value(false));
        } finally {
            Wiring.CLOCK.advance(Duration.ofMinutes(-61));
        }
    }

    @Test
    void aLoopbackRedirectServesDesktopClients() throws Exception {
        String loopback = "http://localhost:53682/callback";
        String clientId = registerClient("Claude Code", loopback);
        Map<String, String> params = authorizeParams(clientId);
        params.remove("redirect_uri"); // the only registered URI is used
        params.remove("state");
        params.remove("resource");
        params.remove("scope");
        String location = mvc.perform(signIn(params, PASSWORD, SSN))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith(loopback + "?code=").doesNotContain("state=");
    }

    // ------------------------------------------------------------------ sign-in refusals

    @Test
    void aWrongPasswordOrSsnShowsTheFormAgain() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);
        for (String[] attempt : new String[][] {{"wrong password", SSN}, {PASSWORD, "234-56-7890"}, {PASSWORD, "not an ssn"}}) {
            mvc.perform(signIn(authorizeParams(clientId), attempt[0], attempt[1]))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(Matchers.containsString(OAuthController.SIGN_IN_FAILED)))
                    .andExpect(content().string(Matchers.not(Matchers.containsString(attempt[0].equals(PASSWORD) ? PASSWORD : "wrong password"))));
        }
    }

    @Test
    void denyingGoesBackWithAccessDenied() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);
        Map<String, String> form = new LinkedHashMap<>(authorizeParams(clientId));
        form.put("decision", "deny");
        String location = mvc.perform(withParams(post("/oauth/authorize").contentType(MediaType.APPLICATION_FORM_URLENCODED), form))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        assertThat(query(location)).containsEntry("error", "access_denied").containsEntry("state", "state-1");
    }

    @Test
    void theClientNameIsEscaped() throws Exception {
        String clientId = registerClient("<b>x</b>", CALLBACK);
        mvc.perform(withParams(get("/oauth/authorize"), authorizeParams(clientId)))
                .andExpect(content().string(Matchers.containsString("&lt;b&gt;x&lt;/b&gt;")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<b>x</b>"))));
    }

    // ------------------------------------------------------------------ bad authorization requests

    @Test
    void anUnknownClientOrRedirectIsShownNotRedirected() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);

        Map<String, String> unknown = authorizeParams("forged-client");
        mvc.perform(withParams(get("/oauth/authorize"), unknown))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(Matchers.containsString("not registered")));

        Map<String, String> otherRedirect = authorizeParams(clientId);
        otherRedirect.put("redirect_uri", "https://claude.com/api/mcp/auth_callback");
        mvc.perform(withParams(get("/oauth/authorize"), otherRedirect))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));

        mvc.perform(withParams(post("/oauth/authorize").contentType(MediaType.APPLICATION_FORM_URLENCODED), unknown))
                .andExpect(status().isBadRequest());

        mvc.perform(withParams(get("/oauth/authorize"), authorizeParams(clientId)).param("state", "again"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(Matchers.containsString("repeats the parameter state")));
    }

    @Test
    void otherErrorsGoBackToTheClient() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);
        Map<String, Map.Entry<String, String>> cases = new LinkedHashMap<>();
        cases.put("unsupported_response_type", Map.entry("response_type", "token"));
        cases.put("invalid_request", Map.entry("code_challenge_method", "plain"));
        cases.put("invalid_target", Map.entry("resource", "https://evil.example/mcp"));
        cases.put("invalid_scope", Map.entry("scope", "credit admin"));
        for (Map.Entry<String, Map.Entry<String, String>> c : cases.entrySet()) {
            Map<String, String> params = authorizeParams(clientId);
            params.put(c.getValue().getKey(), c.getValue().getValue());
            String location = mvc.perform(withParams(get("/oauth/authorize"), params))
                    .andExpect(status().isFound())
                    .andReturn().getResponse().getHeader("Location");
            assertThat(query(location)).containsEntry("error", c.getKey()).containsEntry("state", "state-1");
        }

        Map<String, String> missingChallenge = authorizeParams(clientId);
        missingChallenge.remove("code_challenge");
        assertThat(query(mvc.perform(withParams(get("/oauth/authorize"), missingChallenge))
                .andReturn().getResponse().getHeader("Location"))).containsEntry("error", "invalid_request");

        Map<String, String> longState = authorizeParams(clientId);
        longState.put("state", "s".repeat(OAuthService.MAX_STATE_LENGTH + 1));
        assertThat(query(mvc.perform(withParams(get("/oauth/authorize"), longState))
                .andReturn().getResponse().getHeader("Location")))
                .containsEntry("error", "invalid_request").doesNotContainKey("state");
    }

    @Test
    void registrationRefusesRedirectsThatAreNotAllowed() throws Exception {
        for (String uri : List.of("https://evil.example/cb", "http://claude.ai/api/mcp/auth_callback",
                "http://user@localhost:1/cb", "http://localhost:1/cb#frag", "relative/cb", "http://exa mple")) {
            mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"redirect_uris\":[\"" + uri + "\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
        }
        mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON).content("{\"client_name\":\"x\"}"))
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
        mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[\"" + CALLBACK + "\"],\"client_name\":\"" + "n".repeat(101) + "\"}"))
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
        mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[\"" + CALLBACK + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_name").value("An application"));
    }

    // ------------------------------------------------------------------ bad token requests

    @Test
    void theTokenEndpointRefusesEverythingButAGoodCode() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);
        String otherClient = registerClient("Other", CALLBACK);
        String code = code(clientId);

        mvc.perform(tokenRequest(clientId, code, "verifier-" + "y".repeat(40)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(tokenRequest(clientId, code, "short"))
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(tokenRequest(otherClient, code, VERIFIER))
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(tokenRequest(clientId, "garbage", VERIFIER))
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(tokenRequest(clientId, code, VERIFIER).param("redirect_uri", "x"))
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code)
                        .param("redirect_uri", CALLBACK).param("code_verifier", VERIFIER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("refresh_token", "r"))
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("client_id", clientId).param("code", code)
                        .param("redirect_uri", "https://claude.com/api/mcp/auth_callback").param("code_verifier", VERIFIER))
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("client_id", clientId).param("code", code)
                        .param("redirect_uri", CALLBACK).param("code_verifier", VERIFIER)
                        .param("resource", "https://evil.example/mcp"))
                .andExpect(jsonPath("$.error").value("invalid_target"));

        // After all those refusals the code still works once.
        mvc.perform(tokenRequest(clientId, code, VERIFIER)).andExpect(status().isOk());
    }

    @Test
    void aReusedCodeIsRefusedAndRevokesItsGrant() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);
        String code = code(clientId);
        String body = mvc.perform(tokenRequest(clientId, code, VERIFIER)).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");
        mvc.perform(introspect(token)).andExpect(jsonPath("$.active").value(true));

        mvc.perform(tokenRequest(clientId, code, VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(introspect(token)).andExpect(jsonPath("$.active").value(false));

        // Revoking the grant must not make the code usable again: a third try is refused too.
        mvc.perform(tokenRequest(clientId, code, VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void aCodeCannotBeExchangedAgainAfterItsTokenIsRevoked() throws Exception {
        String clientId = registerClient("Claude", CALLBACK);
        String code = code(clientId);
        String body = mvc.perform(tokenRequest(clientId, code, VERIFIER)).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");
        mvc.perform(post("/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token))
                .andExpect(status().isOk());

        mvc.perform(tokenRequest(clientId, code, VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(introspect(token)).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void revokingAnUnknownTokenIsFine() throws Exception {
        mvc.perform(post("/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "nope"))
                .andExpect(status().isOk());
        mvc.perform(post("/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }
}
