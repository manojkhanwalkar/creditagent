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

    /** Right after security headers (so refusals carry them), before any other work. */
    static final int INBOUND_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    @Bean
    @ConditionalOnProperty(name = "credit.inbound.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<InboundLimitFilter> inboundLimitFilter(InboundLimitProperties properties) {
        TokenBucket bucket = new TokenBucket(properties.burst(), properties.ratePerSecond());
        FilterRegistrationBean<InboundLimitFilter> registration =
                new FilterRegistrationBean<>(new InboundLimitFilter(bucket, properties.maxConcurrent()));
        registration.addUrlPatterns("/*");
        registration.setOrder(INBOUND_LIMIT_ORDER);
        return registration;
    }
}
