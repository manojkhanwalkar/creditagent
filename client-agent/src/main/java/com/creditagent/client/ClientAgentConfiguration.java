package com.creditagent.client;

import com.creditagent.clients.RetrySettings;
import com.creditagent.web.support.RequestSizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientAgentProperties.class)
class ClientAgentConfiguration {

    /** An advice request is one SSN in a small JSON object. */
    static final long MAX_REQUEST_BYTES = 4 * 1024;

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_REQUEST_BYTES, "/api/*");
    }

    @Bean
    CreditAdvisorPort creditAdvisorPort(ClientAgentProperties properties) {
        return new A2aCreditAdvisorPort(properties.intelligenceAgentUrl());
    }

    @Bean
    CreditAdviceClient creditAdviceClient(
            CreditAdvisorPort port, JsonMapper jsonMapper, ClientAgentProperties properties, RetrySettings retry) {
        return new CreditAdviceClient(port, jsonMapper, properties.timeout(), retry);
    }
}
