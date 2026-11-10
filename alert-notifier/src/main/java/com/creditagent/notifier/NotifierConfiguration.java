package com.creditagent.notifier;

import com.creditagent.alerts.AlertRepository;
import com.creditagent.alerts.AlertsTable;
import com.creditagent.alerts.DynamoDbAlertRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotifierProperties.class)
class NotifierConfiguration {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(NotifierProperties properties) {
        DynamoDbClient client = AlertsTable.client(properties.dynamodbEndpoint(), properties.region(), properties.dynamodbTimeout());
        if (properties.createTables()) {
            AlertsTable.ensure(client, properties.alertsTable());
        }
        return client;
    }

    @Bean
    AlertRepository alertRepository(DynamoDbClient dynamo, NotifierProperties properties) {
        return new DynamoDbAlertRepository(dynamo, properties.alertsTable());
    }

    /** SMTP when a relay is configured ({@code spring.mail.host}, i.e. MAIL_HOST), else the placeholder. */
    @Bean
    EmailSender emailSender(ObjectProvider<JavaMailSender> mail, NotifierProperties properties,
            @Value("${spring.mail.host:}") String host) {
        // Decided on the host itself: an empty MAIL_HOST must mean "no relay", not a relay at "".
        JavaMailSender sender = host.isBlank() ? null : mail.getIfAvailable();
        return sender == null ? EmailSender.placeholder() : EmailSender.smtp(sender, properties.from());
    }

    @Bean
    AlertNotifier alertNotifier(AlertRepository alerts, EmailSender email, NotifierProperties properties) {
        return new AlertNotifier(alerts, email, properties, Clock.systemUTC(), duration -> Thread.sleep(duration));
    }
}
