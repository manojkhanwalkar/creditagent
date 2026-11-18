package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creditagent.web.auth.otp.LoggingOtpSender;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpProperties;
import com.creditagent.web.auth.otp.OtpSender;
import com.creditagent.web.auth.otp.SesEmailOtpSender;
import com.creditagent.web.auth.otp.SnsSmsOtpSender;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sns.SnsClient;

class OtpConfigurationTest {

    final OtpConfiguration config = new OtpConfiguration();

    static OtpProperties otp(OtpProperties.Delivery delivery, String ses, String sns) {
        return new OtpProperties(delivery, "eu-west-1", ses, sns, "no-reply@creditagent.example", null, null, null,
                Duration.ofSeconds(4), "challenges", null, null, null, null, null, null);
    }

    static AuthProperties auth(String dynamoEndpoint, boolean createTables) {
        return new AuthProperties(TestSecrets.of("jwt"), TestSecrets.of("ssn"), "credit-agent", Duration.ofHours(1),
                Duration.ofMinutes(5), new AuthProperties.DynamoDb(dynamoEndpoint, "us-east-1", "u", "s", createTables, null));
    }

    @Test
    void sesAndSnsCallsAreBoundedAndRetriedWithBackoff() {
        ClientOverrideConfiguration overrides = OtpConfiguration.overrides(Duration.ofSeconds(4));
        assertThat(overrides.apiCallTimeout()).contains(Duration.ofSeconds(4));
        assertThat(overrides.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
        assertThat(overrides.retryMode()).contains(RetryMode.STANDARD);
    }

    @Test
    void awsDeliveryUsesSesForEmailAndSnsForSms() {
        Map<OtpChannel, OtpSender> senders = OtpConfiguration.senders(otp(OtpProperties.Delivery.AWS, null, null),
                auth(null, false), mock(SesV2Client.class), mock(SnsClient.class));
        assertThat(senders.get(OtpChannel.EMAIL)).isInstanceOf(SesEmailOtpSender.class);
        assertThat(senders.get(OtpChannel.SMS)).isInstanceOf(SnsSmsOtpSender.class);
    }

    @Test
    void logDeliveryWorksOnlyAgainstALocalDynamoDb() {
        Map<OtpChannel, OtpSender> senders = OtpConfiguration.senders(otp(OtpProperties.Delivery.LOG, null, null),
                auth("http://dynamodb:8000", true), null, null);
        assertThat(senders.values()).hasSize(2).allMatch(LoggingOtpSender.class::isInstance);

        for (String awsEndpoint : new String[] {null, " "}) {
            assertThatThrownBy(() -> OtpConfiguration.senders(otp(OtpProperties.Delivery.LOG, null, null),
                    auth(awsEndpoint, false), null, null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("local development only");
        }
    }

    @Test
    void clientsAreBuiltForTheRegionAndOptionalEndpoints() {
        try (SesV2Client ses = config.sesClient(otp(OtpProperties.Delivery.AWS, "http://localhost:4566", null));
                SnsClient sns = config.snsClient(otp(OtpProperties.Delivery.AWS, null, "http://localhost:4566"))) {
            assertThat(ses.serviceClientConfiguration().region().id()).isEqualTo("eu-west-1");
            assertThat(ses.serviceClientConfiguration().endpointOverride()).hasValueSatisfying(
                    uri -> assertThat(uri.toString()).isEqualTo("http://localhost:4566"));
            assertThat(sns.serviceClientConfiguration().endpointOverride()).hasValueSatisfying(
                    uri -> assertThat(uri.toString()).isEqualTo("http://localhost:4566"));
        }
        try (SesV2Client ses = config.sesClient(otp(OtpProperties.Delivery.AWS, "", null));
                SnsClient sns = config.snsClient(otp(OtpProperties.Delivery.AWS, null, ""))) {
            assertThat(ses.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(sns.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
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

        assertThat(config.otpChallengeRepository(dynamo, auth(null, false), otp(OtpProperties.Delivery.AWS, null, null)))
                .isInstanceOf(DynamoDbOtpChallengeRepository.class);
        verify(dynamo, never()).describeTable(any(java.util.function.Consumer.class));

        config.otpChallengeRepository(dynamo, auth("http://dynamodb:8000", true), otp(OtpProperties.Delivery.AWS, null, null));
        verify(dynamo).describeTable(any(java.util.function.Consumer.class));
    }

    @Test
    void theServiceBeanIsWiredFromTheProperties() {
        AuthService authService = mock(AuthService.class);
        org.springframework.beans.factory.ObjectProvider<SesV2Client> ses = providerOf(mock(SesV2Client.class));
        org.springframework.beans.factory.ObjectProvider<SnsClient> sns = providerOf(mock(SnsClient.class));
        assertThat(config.otpLoginService(authService, new InMemoryRepositories.OtpChallenges(),
                otp(OtpProperties.Delivery.AWS, null, null), auth(null, false), ses, sns,
                new com.creditagent.common.AuditLog())).isNotNull();
    }

    @SuppressWarnings("unchecked")
    static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
