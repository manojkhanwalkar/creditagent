package com.creditagent.web.kyc;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KycClientProperties.class)
class KycClientConfiguration {

    @Bean
    IdentityVerifier identityVerifier(RestClient.Builder builder, KycClientProperties properties, RetrySettings retry) {
        // Not idempotent: a real provider may bill or record each check, so only a request that
        // provably never arrived (or a 429/503 refusal) is retried.
        return new KycHttpClient(HttpClients.restClient(builder, properties.url(), properties.timeout(), retry, false));
    }
}
