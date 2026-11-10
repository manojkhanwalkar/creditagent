package com.creditagent.web.offers;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.web.CreditScoreService;
import com.creditagent.web.auth.AuthService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OffersProperties.class)
class OffersConfiguration {

    @Bean
    OffersClient offersClient(RestClient.Builder builder, OffersProperties properties, RetrySettings retry) {
        return new OffersClient(HttpClients.restClient(builder, properties.url(), properties.timeout(), retry));
    }

    @Bean
    OffersService offersService(AuthService auth, OffersClient client) {
        return new OffersService(auth, client);
    }

    /** Registers itself with the auth service; the first score is computed on a virtual thread. */
    @Bean
    ScoreRecorder scoreRecorder(AuthService auth, CreditScoreService scores) {
        ScoreRecorder recorder = new ScoreRecorder(auth, scores, task -> Thread.ofVirtual().name("first-score").start(task));
        auth.addRegistrationListener(recorder);
        return recorder;
    }
}
