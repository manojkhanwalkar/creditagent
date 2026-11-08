package com.creditagent.web.auth;

import com.creditagent.web.kyc.IdentityVerifier;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
class AuthConfiguration {

    /** OWASP's minimum work factor for BCrypt is 10; 12 costs roughly 250 ms per hash. */
    static final int BCRYPT_STRENGTH = 12;

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(AuthProperties properties) {
        AuthProperties.DynamoDb config = properties.dynamodb();
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(config.region()))
                .overrideConfiguration(overrides(config));
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(config.endpoint()));
        }
        DynamoDbClient client = builder.build();
        if (config.createTables()) {
            DynamoDbTables.ensure(client, config.usersTable(), config.sessionsTable());
        }
        return client;
    }

    /**
     * Bounded calls, and the SDK's standard retry mode: up to 3 attempts with exponential backoff and
     * jitter on throttling, 5xx and connection errors, all within {@code timeout}. Each attempt may
     * take half of it, so after one attempt that hangs to its limit only one more fits. DynamoDB
     * reads, conditional writes and deletes are safe to repeat.
     */
    static ClientOverrideConfiguration overrides(AuthProperties.DynamoDb config) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(config.timeout())
                .apiCallAttemptTimeout(config.timeout().dividedBy(2))
                .retryStrategy(RetryMode.STANDARD)
                .build();
    }

    @Bean
    UserRepository userRepository(DynamoDbClient dynamo, AuthProperties properties) {
        return new DynamoDbUserRepository(dynamo, properties.dynamodb().usersTable());
    }

    @Bean
    SessionRepository sessionRepository(DynamoDbClient dynamo, AuthProperties properties) {
        return new DynamoDbSessionRepository(dynamo, properties.dynamodb().sessionsTable());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    AuthService authService(
            UserRepository users,
            SessionRepository sessions,
            PasswordEncoder passwords,
            IdentityVerifier identity,
            AuthProperties properties) {
        Clock clock = Clock.systemUTC();
        JwtService jwt = new JwtService(properties.jwtSecret(), properties.jwtIssuer(), properties.tokenTtl(), clock);
        return new AuthService(
                users, sessions, passwords, new SsnHasher(properties.ssnHmacKey()), identity, jwt,
                properties.sessionIdleTimeout(), clock);
    }
}
