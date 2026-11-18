package com.creditagent.web.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.ThrottledException;

/** SES and SNS senders against mocked AWS clients (no AWS account needed): the exact requests they send. */
class AwsOtpSendersTest {

    @Test
    void sesSendsAPlainTextUtf8EmailFromTheConfiguredIdentity() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());

        new SesEmailOtpSender(ses, "CreditAgent <no-reply@creditagent.example>", "otp-events")
                .send("avery@example.com", "042917", Duration.ofMinutes(5));

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest request = sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("CreditAgent <no-reply@creditagent.example>");
        assertThat(request.destination().toAddresses()).containsExactly("avery@example.com");
        assertThat(request.destination().ccAddresses()).isEmpty();
        assertThat(request.configurationSetName()).isEqualTo("otp-events");
        assertThat(request.content().simple().subject().data()).isEqualTo(SesEmailOtpSender.SUBJECT).doesNotContain("042917");
        assertThat(request.content().simple().subject().charset()).isEqualTo("UTF-8");
        assertThat(request.content().simple().body().text().data())
                .contains("042917").contains("expires in 5 minutes").contains("did not just try to sign in");
        assertThat(request.content().simple().body().html()).isNull();
    }

    @Test
    void sesLeavesOutABlankConfigurationSet() {
        SesV2Client ses = mock(SesV2Client.class);
        new SesEmailOtpSender(ses, "no-reply@creditagent.example", " ").send("a@example.com", "123456", Duration.ofMinutes(5));
        new SesEmailOtpSender(ses, "no-reply@creditagent.example", null).send("a@example.com", "123456", Duration.ofMinutes(5));

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses, org.mockito.Mockito.times(2)).sendEmail(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(r -> assertThat(r.configurationSetName()).isNull());
    }

    @Test
    void anSesRefusalBecomesADeliveryFailureWithoutTheAddressOrCode() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(MessageRejectedException.builder().statusCode(400)
                        .message("Email address is not verified: a@example.com").build());

        assertThatThrownBy(() -> new SesEmailOtpSender(ses, "no-reply@creditagent.example", null)
                .send("a@example.com", "123456", Duration.ofMinutes(5)))
                .isInstanceOf(OtpDeliveryException.class)
                .hasMessage("Could not send a sign-in code by EMAIL")
                .hasCauseInstanceOf(MessageRejectedException.class)
                .satisfies(e -> assertThat(((OtpDeliveryException) e).refused()).isTrue());
    }

    @Test
    void onlyAClearRejectionCountsAsNotDelivered() {
        assertThat(OtpDeliveryException.clearlyRefused(
                MessageRejectedException.builder().statusCode(400).build())).isTrue();
        // Throttling, 5xx, timeouts and client errors may have delivered the message.
        assertThat(OtpDeliveryException.clearlyRefused(ThrottledException.builder().statusCode(400).build())).isFalse();
        assertThat(OtpDeliveryException.clearlyRefused(software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                .statusCode(400).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("Throttled").build()).build())).isFalse();
        assertThat(OtpDeliveryException.clearlyRefused(software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                .statusCode(400).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("InvalidParameter").build()).build())).isTrue();
        assertThat(OtpDeliveryException.clearlyRefused(
                software.amazon.awssdk.awscore.exception.AwsServiceException.builder().statusCode(503).build())).isFalse();
        assertThat(OtpDeliveryException.clearlyRefused(SdkClientException.create("timed out"))).isFalse();
        assertThat(OtpDeliveryException.clearlyRefused(
                software.amazon.awssdk.core.exception.ApiCallTimeoutException.create(5000))).isFalse();
        assertThat(OtpDeliveryException.clearlyRefused(new RuntimeException())).isFalse();
        OtpDeliveryException later = new OtpDeliveryException(OtpChannel.SMS, SdkClientException.create("x"))
                .withRetryAfter(Duration.ofSeconds(30));
        assertThat(later.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(later.channel()).isEqualTo(OtpChannel.SMS);
        assertThat(later.refused()).isFalse();
    }

    @Test
    void snsPublishesATransactionalTextStraightToThePhone() {
        SnsClient sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().messageId("m-2").build());

        new SnsSmsOtpSender(sns, "CredAgent", "+18885550100").send("+12065550100", "042917", Duration.ofMinutes(5));

        ArgumentCaptor<PublishRequest> sent = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(sent.capture());
        PublishRequest request = sent.getValue();
        assertThat(request.phoneNumber()).isEqualTo("+12065550100");
        assertThat(request.topicArn()).isNull();
        assertThat(request.targetArn()).isNull();
        assertThat(request.message()).startsWith("042917 is your CreditAgent sign-in code").hasSizeLessThanOrEqualTo(160);
        assertThat(request.messageAttributes().get(SnsSmsOtpSender.SMS_TYPE).stringValue()).isEqualTo("Transactional");
        assertThat(request.messageAttributes().get(SnsSmsOtpSender.SENDER_ID).stringValue()).isEqualTo("CredAgent");
        assertThat(request.messageAttributes().get(SnsSmsOtpSender.ORIGINATION_NUMBER).stringValue()).isEqualTo("+18885550100");
        assertThat(request.messageAttributes().values()).allSatisfy(v -> assertThat(v.dataType()).isEqualTo("String"));
    }

    @Test
    void snsLeavesOutBlankSenderIdAndOriginationNumber() {
        SnsClient sns = mock(SnsClient.class);
        new SnsSmsOtpSender(sns, "", null).send("+12065550100", "123456", Duration.ofMinutes(5));

        ArgumentCaptor<PublishRequest> sent = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(sent.capture());
        assertThat(sent.getValue().messageAttributes()).containsOnlyKeys(SnsSmsOtpSender.SMS_TYPE);
    }

    @Test
    void anSnsFailureBecomesADeliveryFailure() {
        SnsClient sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class))).thenThrow(ThrottledException.builder().message("slow down").build());
        SnsSmsOtpSender sender = new SnsSmsOtpSender(sns, null, null);
        assertThatThrownBy(() -> sender.send("+12065550100", "123456", Duration.ofMinutes(5)))
                .isInstanceOf(OtpDeliveryException.class).hasMessage("Could not send a sign-in code by SMS");

        when(sns.publish(any(PublishRequest.class))).thenThrow(SdkClientException.create("timed out"));
        assertThatThrownBy(() -> sender.send("+12065550100", "123456", Duration.ofMinutes(5)))
                .isInstanceOf(OtpDeliveryException.class).hasCauseInstanceOf(SdkClientException.class);
    }

    @Test
    void theLoggingSenderNeverThrows() {
        new LoggingOtpSender(OtpChannel.SMS).send("+12065550100", "123456", Duration.ofMinutes(5));
        assertThatThrownBy(() -> new LoggingOtpSender(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void messagesStateAtLeastOneMinute() {
        assertThat(OtpSender.text("123456", Duration.ofSeconds(20), true)).contains("expires in 1 minutes");
        assertThat(OtpSender.text("123456", Duration.ofMinutes(10), false)).contains("expires in 10 minutes");
    }
}
