package com.creditagent.web.oauth;

import com.creditagent.web.auth.AuthService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProperties.class)
class OAuthConfiguration {

    @Bean
    OAuthService oauthService(OAuthProperties properties, AuthService auth) {
        return new OAuthService(properties, auth, Clock.systemUTC());
    }
}
