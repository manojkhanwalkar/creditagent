package com.creditagent.alerts;

import java.time.Clock;
import java.util.HashSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitorProperties.class)
class MonitorConfiguration {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(MonitorProperties properties) {
        DynamoDbClient client = AlertsTable.client(properties.dynamodbEndpoint(), properties.region(), properties.dynamodbTimeout());
        if (properties.createTables()) {
            AlertsTable.ensure(client, properties.alertsTable());
        }
        return client;
    }

    @Bean
    AlertRepository alertRepository(DynamoDbClient dynamo, MonitorProperties properties) {
        return new DynamoDbAlertRepository(dynamo, properties.alertsTable());
    }

    /** The placeholder: contacts nothing. Replace this bean with a real source. */
    @Bean
    DarkWebSource darkWebSource(MonitorProperties properties) {
        return DarkWebSource.placeholder(new HashSet<>(properties.placeholderExposed()), properties.placeholderRate());
    }

    @Bean
    DarkWebMonitor darkWebMonitor(DynamoDbClient dynamo, DarkWebSource source, AlertRepository alerts,
            MonitorProperties properties) {
        return new DarkWebMonitor(WatchList.users(dynamo, properties.usersTable()), source, alerts, properties,
                Clock.systemUTC());
    }
}
