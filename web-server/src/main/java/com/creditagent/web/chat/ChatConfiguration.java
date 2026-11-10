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
