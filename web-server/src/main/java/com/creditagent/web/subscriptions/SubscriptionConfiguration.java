package com.creditagent.web.subscriptions;

import com.creditagent.common.AuditLog;
import com.creditagent.subscriptions.DynamoDbSubscriptionRepository;
import com.creditagent.subscriptions.SubscriptionRepository;
import com.creditagent.subscriptions.SubscriptionsTable;
import com.creditagent.web.auth.AuthProperties;
import com.creditagent.web.auth.AuthService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Wires subscriptions ({@code credit.subscriptions.*}): the Subscriptions table (created at start-up when
 * {@code createTables} is on), the payment gateway chosen by {@code PAYMENTS_PROVIDER} behind a 30 s
 * availability cache, and {@link SubscriptionService}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubscriptionProperties.class)
class SubscriptionConfiguration {

    @Bean
    SubscriptionRepository subscriptionRepository(DynamoDbClient dynamo, AuthProperties auth, SubscriptionProperties properties) {
        if (auth.dynamodb().createTables()) {
            SubscriptionsTable.ensure(dynamo, properties.table());
        }
        return new DynamoDbSubscriptionRepository(dynamo, properties.table());
    }

    @Bean
    PaymentGateway paymentGateway(SubscriptionProperties properties, AuthProperties auth) {
        return new CachingPaymentGateway(gateway(properties, auth), CachingPaymentGateway.DEFAULT_TTL, Clock.systemUTC());
    }

    /** The test gateway gives subscriptions away, so it is refused unless DynamoDB is local. */
    static PaymentGateway gateway(SubscriptionProperties properties, AuthProperties auth) {
        return switch (properties.provider()) {
            case NONE -> PaymentGateway.none();
            case TEST -> {
                if (!auth.dynamodb().local()) {
                    throw new IllegalStateException("PAYMENTS_PROVIDER=test is for local development only and needs"
                            + " DYNAMODB_ENDPOINT on localhost or the compose dynamodb service");
                }
                yield PaymentGateway.test();
            }
        };
    }

    @Bean
    SubscriptionService subscriptionService(AuthService auth, SubscriptionRepository store, PaymentGateway payments,
            SubscriptionProperties properties, AuditLog audit) {
        return new SubscriptionService(auth, store, payments, properties, audit, Clock.systemUTC());
    }
}
