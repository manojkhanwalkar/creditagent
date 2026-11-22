package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.creditagent.notifications.NotificationProperties.EmailProvider;
import com.creditagent.notifications.NotificationProperties.SmsProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sns.SnsClient;

/** Provider choice, the local-only guard on the log senders, the TLS rule and the property defaults. */
class NotificationConfigurationTest {

    static NotificationProperties props(EmailProvider email, SmsProvider sms, String dynamodbEndpoint) {
        return new NotificationProperties("t".repeat(40), email, sms, null, null, null, null, null, null, null, null, null,
                dynamodbEndpoint, null, null, null, null);
    }

    @Test
    void eachChannelGetsItsConfiguredProvider() {
        var aws = NotificationConfiguration.senders(props(EmailProvider.SES, SmsProvider.SNS, ""), mock(SesV2Client.class),
                mock(SnsClient.class), null, "", true);
        assertThat(aws.get(Channel.EMAIL)).isInstanceOf(Senders.Ses.class);
        assertThat(aws.get(Channel.SMS)).isInstanceOf(Senders.Sns.class);

        var compose = NotificationConfiguration.senders(props(EmailProvider.SMTP, SmsProvider.LOG, "http://dynamodb:8000"),
                null, null, mock(JavaMailSender.class), "mailpit", false);
        assertThat(compose.get(Channel.EMAIL)).isInstanceOf(Senders.Smtp.class);
        assertThat(compose.get(Channel.SMS)).isInstanceOf(Senders.Logging.class);

        var local = NotificationConfiguration.senders(props(EmailProvider.LOG, SmsProvider.LOG, "http://localhost:8000"),
                null, null, null, "", false);
        assertThat(local.get(Channel.EMAIL)).isInstanceOf(Senders.Logging.class);
    }

    @Test
    void theLogSendersAreRefusedUnlessDynamoDbIsLocal() {
        for (String endpoint : new String[] {"", null, "https://dynamodb.us-east-1.amazonaws.com",
                "https://vpce-1.dynamodb.us-east-1.vpce.amazonaws.com", "http://local host:8000", "not a uri ::"}) {
            assertThatThrownBy(() -> NotificationConfiguration.senders(props(EmailProvider.SES, SmsProvider.LOG, endpoint),
                    mock(SesV2Client.class), null, null, "", true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("local development only");
            assertThatThrownBy(() -> NotificationConfiguration.senders(props(EmailProvider.LOG, SmsProvider.SNS, endpoint),
                    null, mock(SnsClient.class), null, "", true))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String endpoint : new String[] {"http://localhost:8000", "http://127.0.0.1:8000", "http://[::1]:8000",
                "http://dynamodb:8000", "http://DynamoDB-Local:8000", " http://localhost:8000 "}) {
            assertThat(NotificationsTable.local(endpoint)).as(endpoint).isTrue();
        }
        assertThat(NotificationsTable.local("http:///nohost")).isFalse();
    }

    @Test
    void smtpNeedsARelayAndTlsUnlessItIsTheLocalCatcher() {
        assertThatThrownBy(() -> NotificationConfiguration.senders(props(EmailProvider.SMTP, SmsProvider.SNS, ""),
                null, mock(SnsClient.class), mock(JavaMailSender.class), " ", true))
                .hasMessageContaining("MAIL_HOST");
        assertThatThrownBy(() -> NotificationConfiguration.senders(props(EmailProvider.SMTP, SmsProvider.SNS, ""),
                null, mock(SnsClient.class), null, "smtp.example.com", true))
                .hasMessageContaining("MAIL_HOST");
        assertThatThrownBy(() -> NotificationConfiguration.senders(props(EmailProvider.SMTP, SmsProvider.SNS, ""),
                null, mock(SnsClient.class), mock(JavaMailSender.class), "smtp.example.com", false))
                .hasMessageContaining("MAIL_STARTTLS must be true");
        assertThatCode(() -> NotificationConfiguration.requireTlsUnlessLocal("smtp.example.com", true)).doesNotThrowAnyException();
        assertThatCode(() -> NotificationConfiguration.requireTlsUnlessLocal(" Mailpit ", false)).doesNotThrowAnyException();
        assertThatCode(() -> NotificationConfiguration.requireTlsUnlessLocal(null, false)).doesNotThrowAnyException();
    }

    @Test
    void propertiesHaveSafeDefaultsAndRefuseALeaseShorterThanASend() {
        NotificationProperties defaults = new NotificationProperties(null, null, null, " ", null, null, " ", null, null, null,
                null, " ", null, null, null, null, null);
        assertThat(defaults.email()).isEqualTo(EmailProvider.SES);
        assertThat(defaults.sms()).isEqualTo(SmsProvider.SNS);
        assertThat(defaults.region()).isEqualTo("us-east-1");
        assertThat(defaults.table()).isEqualTo("credit-agent-notifications");
        assertThat(defaults.retention()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.createTables()).isFalse();
        assertThat(defaults.serviceToken()).isEmpty();
        assertThat(defaults.logsAnything()).isFalse();
        assertThat(new NotificationProperties("secret-token-value", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null).toString()).doesNotContain("secret-token-value");
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, null, null, null, null, null, null, null,
                Duration.ofSeconds(30), null, null, Duration.ofSeconds(30), null, null, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, null, null, null, null, null, null, null,
                Duration.ZERO, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, Duration.ZERO, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAwsClientsAreBoundedWithStandardRetries() {
        var overrides = NotificationsTable.overrides(Duration.ofSeconds(4));
        assertThat(overrides.apiCallTimeout()).contains(Duration.ofSeconds(4));
        assertThat(overrides.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
        assertThat(overrides.retryMode()).contains(software.amazon.awssdk.core.retry.RetryMode.STANDARD);
        NotificationConfiguration configuration = new NotificationConfiguration();
        NotificationProperties properties = new NotificationProperties(null, null, null, null, "http://localhost:4566",
                "http://localhost:4566", null, null, null, null, null, null, "http://localhost:8000", null, null, null, null);
        try (SesV2Client ses = configuration.sesClient(properties); SnsClient sns = configuration.snsClient(properties);
                var dynamo = configuration.dynamoDbClient(properties)) {
            assertThat(ses).isNotNull();
            assertThat(sns).isNotNull();
            assertThat(configuration.notificationStore(dynamo, properties)).isInstanceOf(DynamoDbNotificationStore.class);
        }
        NotificationProperties aws = props(EmailProvider.SES, SmsProvider.SNS, "");
        try (SesV2Client ses = configuration.sesClient(aws); SnsClient sns = configuration.snsClient(aws)) {
            assertThat(ses).isNotNull();
            assertThat(sns).isNotNull();
        }
    }
}
