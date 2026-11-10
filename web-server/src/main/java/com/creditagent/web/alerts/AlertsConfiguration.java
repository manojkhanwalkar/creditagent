package com.creditagent.web.alerts;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.web.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertsProperties.class)
class AlertsConfiguration {

    @Bean
    AlertsService alertsService(AuthService auth, RestClient.Builder builder, AlertsProperties properties,
            RetrySettings retry, @Value("${credit.internal.token:}") String serviceToken) {
        AlertsClient retrying = new AlertsClient(
                HttpClients.restClient(builder, properties.url(), properties.timeout(), retry), serviceToken);
        AlertsClient once = new AlertsClient(
                HttpClients.restClient(builder, properties.url(), properties.loginTimeout(), RetrySettings.none()), serviceToken);
        return new AlertsService(auth, retrying, once);
    }
}
