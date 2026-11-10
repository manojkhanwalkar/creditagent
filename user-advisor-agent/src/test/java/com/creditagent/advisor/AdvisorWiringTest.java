package com.creditagent.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.advisor.memory.ConversationMemory;
import com.creditagent.web.support.ServiceToken;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdvisorWiringTest {

    @Test
    void propertiesHaveDefaultsAndRefuseNonsense() {
        AdvisorProperties defaults = new AdvisorProperties(" ", null, null, null, null, null, null);
        assertThat(defaults.serviceToken()).isNull();
        assertThat(defaults.memoryTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.maxConversations()).isEqualTo(10_000);
        assertThat(defaults.maxTurns()).isEqualTo(20);
        assertThat(defaults.toString()).contains("<redacted>");
        assertThatThrownBy(() -> new AdvisorProperties(null, Duration.ZERO, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvisorProperties(null, null, null, null, 1.5, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutATokenTheApiIsOff() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(new ServiceToken(""));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("DELETE", "/internal/v1/conversations/abc"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void encodedActuatorPathsDoNotSkipTheCheck() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(new ServiceToken("service-token-service-token-service-token"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/../internal/v1/conversations/x/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void theSweeperDropsExpiredConversations() {
        ConversationMemory memory = new ConversationMemory(Duration.ofNanos(1), 5, 5, 0.99, () -> 0.1, Clock.systemUTC());
        memory.remember("conversation-0123456789", new ConversationMemory.Turn("q", "a"));
        new AdvisorConfiguration.MemorySweeper(memory).sweep();
        assertThat(memory.size()).isZero();
    }
}
