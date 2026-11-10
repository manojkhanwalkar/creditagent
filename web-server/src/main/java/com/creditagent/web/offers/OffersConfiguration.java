package com.creditagent.web.offers;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.web.CreditScoreService;
import com.creditagent.web.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OffersProperties.class)
class OffersConfiguration {

    @Bean
    OffersService offersService(AuthService auth, RestClient.Builder builder, OffersProperties properties, RetrySettings retry,
            @Value("${credit.internal.token:}") String serviceToken) {
        OffersClient retrying = new OffersClient(
                HttpClients.restClient(builder, properties.url(), properties.timeout(), retry), serviceToken);
        // Login: one try only. A read timeout is not retried, so a hung service costs a login loginTimeout.
        OffersClient once = new OffersClient(
                HttpClients.restClient(builder, properties.url(), properties.loginTimeout(), RetrySettings.none()), serviceToken);
        return new OffersService(auth, retrying, once);
    }

    /** Registers itself with the auth service; the first score is computed on a virtual thread. */
    @Bean
    ScoreRecorder scoreRecorder(AuthService auth, CreditScoreService scores) {
        return register(auth, new ScoreRecorder(auth, scores, task -> Thread.ofVirtual().name("first-score").start(task)));
    }

    static ScoreRecorder register(AuthService auth, ScoreRecorder recorder) {
        auth.addRegistrationListener(recorder);
        return recorder;
    }
}
