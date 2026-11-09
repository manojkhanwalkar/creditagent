package com.creditagent.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ServiceTokenTest {

    static final String TOKEN = "service-token-service-token-service-token";

    @Test
    void matchesOnlyTheExactBearerToken() {
        ServiceToken token = new ServiceToken(TOKEN);
        assertThat(token.isConfigured()).isTrue();
        assertThat(token.matches("Bearer " + TOKEN)).isTrue();
        assertThat(token.matches("bearer  " + TOKEN + " ")).isTrue();
        assertThat(token.matches("Bearer " + TOKEN + "x")).isFalse();
        assertThat(token.matches("Basic " + TOKEN)).isFalse();
        assertThat(token.matches(TOKEN)).isFalse();
        assertThat(token.matches(null)).isFalse();
    }

    @Test
    void withoutATokenNothingMatches() {
        ServiceToken none = new ServiceToken("");
        assertThat(none.isConfigured()).isFalse();
        assertThat(none.matches("Bearer ")).isFalse();
        assertThat(new ServiceToken(null).matches("Bearer x")).isFalse();
    }

    @Test
    void aShortTokenIsRefusedAndTheTokenIsNeverPrinted() {
        assertThatThrownBy(() -> new ServiceToken("short")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ServiceToken(TOKEN).toString()).doesNotContain(TOKEN);
    }
}
