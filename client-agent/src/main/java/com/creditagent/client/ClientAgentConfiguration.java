package com.creditagent.client;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientAgentProperties.class)
class ClientAgentConfiguration {

    @Bean
    CreditAdvisorPort creditAdvisorPort(ClientAgentProperties properties) {
        return new A2aCreditAdvisorPort(properties.intelligenceAgentUrl());
    }

    @Bean
    CreditAdviceClient creditAdviceClient(CreditAdvisorPort port, JsonMapper jsonMapper) {
        return new CreditAdviceClient(port, jsonMapper);
    }
}
