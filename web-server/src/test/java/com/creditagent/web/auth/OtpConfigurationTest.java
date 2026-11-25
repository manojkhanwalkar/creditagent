package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creditagent.clients.RetrySettings;
import com.creditagent.common.AuditLog;
import com.creditagent.web.auth.otp.OtpProperties;
import com.creditagent.web.notify.NotificationGateway;
import com.creditagent.web.notify.NotificationsProperties;
import com.creditagent.web.notify.RemoteNotificationGateway;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;

class OtpConfigurationTest {

    final OtpConfiguration config = new OtpConfiguration();

    static OtpProperties otp() {
        return new OtpProperties("challenges", null, null, null, null, null, null);
    }

    static AuthProperties auth(String dynamoEndpoint, boolean createTables) {
        return new AuthProperties(TestSecrets.of("jwt"), TestSecrets.of("ssn"), "credit-agent", Duration.ofHours(1),
                Duration.ofMinutes(5), new AuthProperties.DynamoDb(dynamoEndpoint, "us-east-1", "u", "s", createTables, null));
    }

    @Test
    void theCodeKeyIsDerivedFromButNotEqualToTheJwtSecret() {
        String secret = TestSecrets.of("jwt");
        assertThat(OtpConfiguration.codeKey(secret)).hasSize(32).isEqualTo(OtpConfiguration.codeKey(secret))
                .isNotEqualTo(OtpConfiguration.codeKey(TestSecrets.of("other")))
                .isNotEqualTo(java.util.Arrays.copyOf(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), 32));
    }

    @Test
    void theChallengeTableIsCreatedOnlyWhenAskedTo() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        when(dynamo.describeTable(any(java.util.function.Consumer.class))).thenReturn(DescribeTableResponse.builder().build());

        assertThat(config.otpChallengeRepository(dynamo, auth(null, false), otp()))
                .isInstanceOf(DynamoDbOtpChallengeRepository.class);
        verify(dynamo, never()).describeTable(any(java.util.function.Consumer.class));

        config.otpChallengeRepository(dynamo, auth("http://dynamodb:8000", true), otp());
        verify(dynamo).describeTable(any(java.util.function.Consumer.class));
    }

    @Test
    void codesAreSentThroughTheServiceAndNeedTheToken() {
        NotificationGateway gateway = config.notificationGateway(
                new NotificationsProperties("http://notification-service:8094", Duration.ofSeconds(10), null),
                RestClient.builder(), RetrySettings.DEFAULTS, TestSecrets.of("internal"));
        assertThat(gateway).isInstanceOf(RemoteNotificationGateway.class);
        assertThat(config.otpLoginService(mock(AuthService.class), new InMemoryRepositories.OtpChallenges(), otp(),
                auth(null, false), gateway, new AuditLog())).isNotNull();
        assertThatThrownBy(() -> config.notificationGateway(new NotificationsProperties(null, null, null),
                RestClient.builder(), RetrySettings.DEFAULTS, " ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    void theServiceDefaultsAreLocalAndBounded() {
        NotificationsProperties defaults = new NotificationsProperties(null, null, null);
        assertThat(defaults.url()).isEqualTo("http://localhost:8094");
        assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(() -> new NotificationsProperties(null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationsProperties(null, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Only what the context gets from elsewhere in the app: no SES or SNS client, no AWS credentials. */
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OtpConfiguration.class)
            .withBean(DynamoDbClient.class, () -> mock(DynamoDbClient.class))
            .withBean(AuthProperties.class, () -> auth(null, false))
            .withBean(AuthService.class, () -> mock(AuthService.class))
            .withBean(AuditLog.class, AuditLog::new)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(RetrySettings.class, () -> RetrySettings.DEFAULTS)
            .withPropertyValues("credit.auth.otp.table=challenges");

    @Test
    void theContextStartsWithOnlyTheServiceUrlAndToken() {
        runner.withPropertyValues("credit.notifications.url=http://notification-service:8094",
                "credit.internal.token=" + TestSecrets.of("internal")).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OtpLoginService.class);
                    assertThat(context.getBean(NotificationGateway.class)).isInstanceOf(RemoteNotificationGateway.class);
                    assertThat(context.getBeanNamesForType(Object.class)).noneMatch(name -> name.matches("(?i).*(ses|sns).*client.*"));
                });
        // Without the token, sign-in could never send a code: the context refuses to start.
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theServiceUrlMustBeHttpsUnlessLocal() {
        for (String ok : new String[] {"http://localhost:8094", "http://127.0.0.1:8094", "http://[::1]:8094",
                "http://notification-service:8094", "https://notifications.internal.example", "HTTPS://Notify.Example/"}) {
            assertThat(new NotificationsProperties(ok, null, null).url()).isEqualTo(ok);
        }
        for (String bad : new String[] {"http://notifications.internal.example", "http://10.0.0.5:8094", "ftp://localhost",
                "notification-service:8094", "/internal", "http://", "http://exa mple"}) {
            assertThatThrownBy(() -> new NotificationsProperties(bad, null, null)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOTIFICATIONS_URL");
        }
        runner.withPropertyValues("credit.notifications.url=http://notify.example.com",
                "credit.internal.token=" + TestSecrets.of("internal")).run(context -> assertThat(context).hasFailed());
    }
}
