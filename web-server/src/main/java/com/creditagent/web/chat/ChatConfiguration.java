package com.creditagent.web.chat;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.web.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
class ChatConfiguration {

    @Bean
    AdvisorClient advisorClient(RestClient.Builder builder, ChatProperties properties, RetrySettings retry,
            @Value("${credit.internal.token:}") String serviceToken) {
        return buildAdvisorClient(builder, properties, retry, serviceToken);
    }

    /**
     * A chat message adds to the conversation, so its client retries only requests that never arrived
     * (and 429 or 503). Forgetting is safe to repeat, so its client retries any transient failure.
     */
    static AdvisorClient buildAdvisorClient(RestClient.Builder builder, ChatProperties properties, RetrySettings retry,
            String serviceToken) {
        return new AdvisorClient(
                HttpClients.restClient(builder, properties.advisorUrl(), properties.timeout(), retry, false),
                HttpClients.restClient(builder, properties.advisorUrl(), properties.timeout(), retry, true),
                serviceToken);
    }

    @Bean
    ChatService chatService(AuthService auth, CreditReportSource reports, AdvisorClient advisor) {
        return new ChatService(auth, reports, advisor);
    }
}
