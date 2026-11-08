package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.web.support.WebSupportAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

class FilterOrderTest {

    @Test
    void securityHeadersThenPerClientLimitThenSharedLimitsThenAuthentication() {
        int headers = SecurityHeadersFilter.class.getAnnotation(Order.class).value();
        int perClient = RateLimitFilter.class.getAnnotation(Order.class).value();
        int auth = com.creditagent.web.auth.AuthenticationFilterOrder.value();

        assertThat(headers).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        // One client over its own limit must be stopped before it can drain the shared bucket.
        assertThat(perClient).isGreaterThan(headers).isLessThan(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER);
        assertThat(auth).isGreaterThan(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER + 1);
    }
}
