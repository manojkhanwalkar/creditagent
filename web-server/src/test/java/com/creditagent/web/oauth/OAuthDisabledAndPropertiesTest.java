package com.creditagent.web.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.TestSecrets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

class OAuthDisabledAndPropertiesTest {

    final AuthService auth = mock(AuthService.class);
    final OAuthService disabled = new OAuthService(
            new OAuthProperties(null, null, null, null, null, null, null), auth, Clock.systemUTC());

    @Test
    void withoutAKeyEveryEndpointIs404AndNoTokenIsActive() {
        OAuthController controller = new OAuthController(disabled, auth);
        assertThat(disabled.enabled()).isFalse();
        for (Runnable call : List.<Runnable>of(
                controller::metadata,
                () -> controller.register(new OAuthController.RegistrationRequest(List.of(), null)),
                () -> controller.authorize(new LinkedMultiValueMap<>(), new MockHttpServletResponse()),
                () -> controller.decide(new LinkedMultiValueMap<>()),
                () -> controller.token(new LinkedMultiValueMap<>()),
                () -> controller.revoke("t"))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }

        ConnectorApiController api = new ConnectorApiController(disabled, auth);
        assertThat(api.introspect(new ConnectorApiController.TokenRequest("t")).active()).isFalse();
        assertThatThrownBy(() -> api.reportStatus(new ConnectorApiController.TokenRequest("t")))
                .isInstanceOf(ConnectorApiController.InactiveTokenException.class);
        verifyNoInteractions(auth);
    }

    @Test
    void propertiesHaveSafeDefaults() {
        OAuthProperties defaults = new OAuthProperties(" ", "", " ", List.of(), null, null, null);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.issuer()).isEqualTo("http://localhost:8080");
        assertThat(defaults.resource()).isEqualTo("http://localhost:8088/mcp");
        assertThat(defaults.allowedRedirectUris()).isEqualTo(OAuthProperties.CLAUDE_CALLBACKS);
        assertThat(defaults.allowLoopbackRedirects()).isTrue();
        assertThat(defaults.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.codeTtl()).isEqualTo(Duration.ofSeconds(60));

        OAuthProperties set = new OAuthProperties(TestSecrets.of("oauth"), "https://a.example/", "https://c.example/mcp/",
                List.of("https://x.example/cb"), false, Duration.ofMinutes(5), Duration.ofSeconds(30));
        assertThat(set.enabled()).isTrue();
        assertThat(set.issuer()).isEqualTo("https://a.example");
        assertThat(set.resource()).isEqualTo("https://c.example/mcp");
        assertThat(set.toString()).doesNotContain(TestSecrets.of("oauth")).contains("<redacted>");
    }

    @Test
    void propertiesRefuseAShortKeyOrNonPositiveLifetimes() {
        assertThatThrownBy(() -> new OAuthProperties("short", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuthProperties(null, null, null, null, null, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuthProperties(null, null, null, null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConsentPageOriginKeepsThePort() {
        assertThat(AuthorizePage.origin("http://localhost:53682/callback?x=1")).isEqualTo("http://localhost:53682");
        assertThat(AuthorizePage.csp("https://claude.ai/api/mcp/auth_callback"))
                .contains("form-action 'self' https://claude.ai").contains("script-src 'self'").contains("frame-ancestors 'none'");
    }

    @Test
    void errorPagesShowOnlyTheirFixedText() {
        for (OAuthService.AuthorizeException.Reason reason : OAuthService.AuthorizeException.Reason.values()) {
            assertThat(AuthorizePage.error(reason)).contains(reason.text()).startsWith("<!doctype html>");
        }
        assertThat(AuthorizePage.consent()).contains("id=\"consent-form\"").doesNotContain("{{");
    }

    @Test
    void pkceNeedsAWellFormedVerifier() {
        assertThat(OAuthService.pkceMatches(null, "x")).isFalse();
        assertThat(OAuthService.pkceMatches("bad verifier with spaces and more than forty three chars", "x")).isFalse();
    }
}
