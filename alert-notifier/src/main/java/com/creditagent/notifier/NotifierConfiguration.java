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
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.properties.mail.smtp.starttls.required:false}") boolean tlsRequired) {
        requireTlsUnlessLocal(host, tlsRequired);
        // Decided on the host itself: an empty MAIL_HOST must mean "no relay", not a relay at "".
        JavaMailSender sender = host.isBlank() ? null : mail.getIfAvailable();
        return sender == null ? EmailSender.placeholder() : EmailSender.smtp(sender, properties.from());
    }

    /** Hosts where plain SMTP is acceptable: the local Mailpit catcher, on this machine or in compose. */
    static final java.util.Set<String> LOCAL_RELAYS = java.util.Set.of("localhost", "127.0.0.1", "::1", "mailpit");

    /**
     * Emails carry users' addresses and findings, and the relay login: refuse to start with TLS off
     * for any relay but a local mail catcher.
     */
    static void requireTlsUnlessLocal(String host, boolean tlsRequired) {
        String name = host == null ? "" : host.strip().toLowerCase(java.util.Locale.ROOT);
        if (!name.isEmpty() && !tlsRequired && !LOCAL_RELAYS.contains(name)) {
            throw new IllegalStateException("MAIL_STARTTLS must be true for the mail relay " + name
                    + " (plain SMTP is allowed only for a local mail catcher)");
        }
    }

    @Bean
    AlertNotifier alertNotifier(AlertRepository alerts, EmailSender email, NotifierProperties properties) {
        return new AlertNotifier(alerts, email, properties, Clock.systemUTC(), duration -> Thread.sleep(duration));
    }
}
