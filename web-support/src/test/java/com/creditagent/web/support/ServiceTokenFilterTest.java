package com.creditagent.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {

    static final String TOKEN = "service-token-service-token-service-token";

    int status(ServiceTokenFilter filter, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void onlyTheServiceTokenGetsThrough() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(new ServiceToken(TOKEN));
        assertThat(status(filter, "/api/v1/offers", "Bearer " + TOKEN)).isEqualTo(200);
        assertThat(status(filter, "/api/v1/offers", "Bearer wrong-wrong-wrong-wrong-wrong-wrong")).isEqualTo(401);
        assertThat(status(filter, "/mcp", null)).isEqualTo(401);
    }

    @Test
    void healthChecksAreOpenButEncodedPathsAreNot() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(new ServiceToken(TOKEN));
        assertThat(status(filter, "/actuator/health", null)).isEqualTo(200);
        assertThat(status(filter, "/actuator/../mcp", null)).isEqualTo(401);
    }

    @Test
    void withoutAConfiguredTokenTheApiIsOff() throws Exception {
        assertThat(status(new ServiceTokenFilter(new ServiceToken("")), "/mcp", "Bearer " + TOKEN)).isEqualTo(404);
    }

    @Test
    void itIsRegisteredOnEveryPathBeforeTheInboundLimits() {
        var registration = ServiceTokenFilter.registration(TOKEN);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isLessThan(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER);
    }
}
