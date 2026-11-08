package com.creditagent.web.support;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Applies {@link InboundLimitFilter} to every service that has this module on its class path, so
 * no service has to wire it by hand. Configure with {@code credit.inbound.*}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InboundLimitProperties.class)
public class WebSupportAutoConfiguration {

    /**
     * After security headers (so refusals carry them) and after any per-client limiter a service has
     * (it must run first, or one client could use up the shared bucket for everyone), but before any
     * other work.
     */
    public static final int INBOUND_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    @ConditionalOnProperty(name = "credit.inbound.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<InboundLimitFilter> inboundLimitFilter(InboundLimitProperties properties) {
        TokenBucket bucket = new TokenBucket(properties.burst(), properties.ratePerSecond());
        FilterRegistrationBean<InboundLimitFilter> registration =
                new FilterRegistrationBean<>(new InboundLimitFilter(bucket, properties.maxConcurrent()));
        registration.addUrlPatterns(properties.urlPatterns().toArray(String[]::new));
        registration.setOrder(INBOUND_LIMIT_ORDER);
        return registration;
    }
}
