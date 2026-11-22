package com.creditagent.notifier;

import com.creditagent.alerts.AlertRepository;
import com.creditagent.alerts.AlertsTable;
import com.creditagent.alerts.DynamoDbAlertRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotifierProperties.class, NotificationsProperties.class})
class NotifierConfiguration {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(NotifierProperties properties) {
        DynamoDbClient client = AlertsTable.client(properties.dynamodbEndpoint(), properties.region(), properties.dynamodbTimeout());
        if (properties.createTables()) {
            AlertsTable.ensure(client, properties.alertsTable());
            com.creditagent.subscriptions.SubscriptionsTable.ensure(client, properties.subscriptionsTable());
        }
        return client;
    }

    @Bean
    AlertRepository alertRepository(DynamoDbClient dynamo, NotifierProperties properties) {
        return new DynamoDbAlertRepository(dynamo, properties.alertsTable());
    }

    /**
     * The notification-service, once the service token is set ({@code INTERNAL_API_TOKEN}); else the
     * placeholder, which sends nothing and leaves every alert unclaimed.
     */
    @Bean
    EmailSender emailSender(NotificationsProperties notifications, @Value("${credit.internal.token:}") String serviceToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            return EmailSender.placeholder();
        }
        return RemoteEmailSender.create(notifications.url(), notifications.timeout(), serviceToken);
    }

    /** Read-only use: the notifier only asks whether an address has ID Protection. */
    @Bean
    com.creditagent.subscriptions.SubscriptionRepository subscriptionRepository(DynamoDbClient dynamo, NotifierProperties properties) {
        return new com.creditagent.subscriptions.DynamoDbSubscriptionRepository(dynamo, properties.subscriptionsTable());
    }

    @Bean
    AlertNotifier alertNotifier(AlertRepository alerts, EmailSender email, NotifierProperties properties,
            com.creditagent.subscriptions.SubscriptionRepository subscriptions) {
        return new AlertNotifier(alerts, email, properties, Clock.systemUTC(), duration -> Thread.sleep(duration),
                (address, now) -> subscriptions.activeForEmail(address, com.creditagent.subscriptions.Product.ID_PROTECTION, now));
    }
}
