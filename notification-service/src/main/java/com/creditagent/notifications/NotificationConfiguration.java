package com.creditagent.notifications;

import com.creditagent.web.support.RequestSizeLimitFilter;
import com.creditagent.web.support.ServiceTokenFilter;
import java.net.URI;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

    static final long MAX_BODY_BYTES = 8 * 1024;

    /** Only holders of INTERNAL_API_TOKEN may send; checked before the inbound limits. */
    @Bean
    FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilter(NotificationProperties properties) {
        return ServiceTokenFilter.registration(properties.serviceToken());
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_BODY_BYTES, NotificationController.PATH);
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(NotificationProperties properties) {
        DynamoDbClient client = NotificationsTable.client(properties.dynamodbEndpoint(), properties.region(),
                properties.dynamodbTimeout());
        if (properties.createTables()) {
            NotificationsTable.ensure(client, properties.table());
        }
        return client;
    }

    @Bean
    NotificationStore notificationStore(DynamoDbClient dynamo, NotificationProperties properties) {
        return new DynamoDbNotificationStore(dynamo, properties.table());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "credit.notifications.email", havingValue = "ses", matchIfMissing = true)
    SesV2Client sesClient(NotificationProperties properties) {
        SesV2ClientBuilder builder = SesV2Client.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(NotificationsTable.overrides(properties.providerTimeout()));
        if (properties.sesEndpoint() != null && !properties.sesEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.sesEndpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "credit.notifications.sms", havingValue = "sns", matchIfMissing = true)
    SnsClient snsClient(NotificationProperties properties) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(NotificationsTable.overrides(properties.providerTimeout()));
        if (properties.snsEndpoint() != null && !properties.snsEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.snsEndpoint()));
        }
        return builder.build();
    }

    @Bean
    NotificationService notificationService(NotificationStore store, NotificationProperties properties,
            ObjectProvider<SesV2Client> ses, ObjectProvider<SnsClient> sns, ObjectProvider<JavaMailSender> mail,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${spring.mail.properties.mail.smtp.starttls.required:false}") boolean tlsRequired) {
        Map<Channel, Sender> senders = senders(properties, ses.getIfAvailable(), sns.getIfAvailable(),
                mail.getIfAvailable(), mailHost, tlsRequired);
        return new NotificationService(store, senders, properties.serviceToken(), Clock.systemUTC(), properties.lease(),
                properties.retention());
    }

    /** The configured provider for each channel; refuses a set-up that would send insecurely or not at all. */
    static Map<Channel, Sender> senders(NotificationProperties properties, SesV2Client ses, SnsClient sns,
            JavaMailSender mail, String mailHost, boolean tlsRequired) {
        if (properties.logsAnything()) {
            requireLocal(properties.dynamodbEndpoint());
            log.warn("A channel is set to log: those messages are not sent. Development only.");
        }
        Map<Channel, Sender> senders = new EnumMap<>(Channel.class);
        senders.put(Channel.EMAIL, switch (properties.email()) {
            case SES -> new Senders.Ses(ses, properties.emailFrom(), properties.sesConfigurationSet());
            case SMTP -> {
                if (mailHost == null || mailHost.isBlank() || mail == null) {
                    throw new IllegalStateException("NOTIFICATIONS_EMAIL=smtp needs MAIL_HOST");
                }
                requireTlsUnlessLocal(mailHost, tlsRequired);
                yield new Senders.Smtp(mail, properties.emailFrom());
            }
            case LOG -> new Senders.Logging(Channel.EMAIL);
        });
        senders.put(Channel.SMS, switch (properties.sms()) {
            case SNS -> new Senders.Sns(sns, properties.smsSenderId(), properties.smsOriginationNumber());
            case LOG -> new Senders.Logging(Channel.SMS);
        });
        return senders;
    }

    /**
     * Log delivery sends nothing, so it is allowed only against a DynamoDB on this machine or in compose,
     * never against AWS (an AWS, VPC or FIPS endpoint override included).
     */
    static void requireLocal(String dynamodbEndpoint) {
        if (!NotificationsTable.local(dynamodbEndpoint)) {
            throw new IllegalStateException("NOTIFICATIONS_EMAIL=log and NOTIFICATIONS_SMS=log are for local development"
                    + " only and need DYNAMODB_ENDPOINT on localhost or the compose dynamodb service");
        }
    }

    /** Hosts where plain SMTP is acceptable: the local Mailpit catcher, on this machine or in compose. */
    static final Set<String> LOCAL_RELAYS = Set.of("localhost", "127.0.0.1", "::1", "mailpit");

    /** Emails carry sign-in codes and the relay login: TLS is required for any relay but a local catcher. */
    static void requireTlsUnlessLocal(String host, boolean tlsRequired) {
        String name = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && !tlsRequired && !LOCAL_RELAYS.contains(name)) {
            throw new IllegalStateException("MAIL_STARTTLS must be true for the mail relay " + name
                    + " (plain SMTP is allowed only for a local mail catcher)");
        }
    }
}
