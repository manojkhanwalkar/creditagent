package com.creditagent.web.kyc;

import com.creditagent.clients.HttpClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KycClientProperties.class)
class KycClientConfiguration {

    @Bean
    IdentityVerifier identityVerifier(RestClient.Builder builder, KycClientProperties properties) {
        return new KycHttpClient(HttpClients.restClient(builder, properties.url(), properties.timeout()));
    }
}
