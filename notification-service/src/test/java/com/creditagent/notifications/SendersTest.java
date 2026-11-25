package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.InvalidParameterException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.ThrottledException;

/** The providers against mocked clients (no AWS account or relay): the requests, and 422 versus 503. */
@ExtendWith(OutputCaptureExtension.class)
class SendersTest {

    static final Message EMAIL = new Message("Your CreditAgent sign-in code", "Your code is 042917.");
    static final Message SMS = new Message(null, "042917 is your CreditAgent sign-in code.");
    static final java.time.Duration TEN_SECONDS = java.time.Duration.ofSeconds(10);

    @Test
    void sesSendsAPlainTextUtf8EmailAndReturnsItsId() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());

        assertThat(new Senders.Ses(ses, "CreditAgent <no-reply@creditagent.example>", "events").send("ada@example.com", EMAIL))
                .isEqualTo("m-1");

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest request = sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("CreditAgent <no-reply@creditagent.example>");
        assertThat(request.destination().toAddresses()).containsExactly("ada@example.com");
        assertThat(request.configurationSetName()).isEqualTo("events");
        assertThat(request.content().simple().subject().data()).isEqualTo(EMAIL.subject());
        assertThat(request.content().simple().body().text().data()).isEqualTo(EMAIL.text());
        assertThat(request.content().simple().body().text().charset()).isEqualTo("UTF-8");
        assertThat(request.content().simple().body().html()).isNull();

        new Senders.Ses(ses, "no-reply@creditagent.example", " ").send("ada@example.com", EMAIL);
        new Senders.Ses(ses, "no-reply@creditagent.example", null).send("ada@example.com", EMAIL);
        ArgumentCaptor<SendEmailRequest> all = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses, org.mockito.Mockito.times(3)).sendEmail(all.capture());
        assertThat(all.getAllValues().subList(1, 3)).allSatisfy(r -> assertThat(r.configurationSetName()).isNull());
    }

    @Test
    void snsPublishesATransactionalTextToTheNumber() {
        SnsClient sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().messageId("s-1").build());

        assertThat(new Senders.Sns(sns, "CrAgent", "+18005550100").send("+15555550100", SMS)).isEqualTo("s-1");
        new Senders.Sns(sns, " ", null).send("+15555550100", SMS);

        ArgumentCaptor<PublishRequest> sent = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns, org.mockito.Mockito.times(2)).publish(sent.capture());
        PublishRequest first = sent.getAllValues().get(0);
        assertThat(first.phoneNumber()).isEqualTo("+15555550100");
        assertThat(first.topicArn()).isNull();
        assertThat(first.message()).isEqualTo(SMS.text());
        assertThat(first.messageAttributes().get(Senders.Sns.SMS_TYPE).stringValue()).isEqualTo("Transactional");
        assertThat(first.messageAttributes().get(Senders.Sns.SENDER_ID).stringValue()).isEqualTo("CrAgent");
        assertThat(first.messageAttributes().get(Senders.Sns.ORIGINATION_NUMBER).stringValue()).isEqualTo("+18005550100");
        assertThat(sent.getAllValues().get(1).messageAttributes()).containsOnlyKeys(Senders.Sns.SMS_TYPE);
    }

    @Test
    void awsRefusalsAre422AndEverythingElseIs503() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(MessageRejectedException.builder().statusCode(400).message("not verified: ada@example.com").build());
        assertThatThrownBy(() -> new Senders.Ses(ses, "f@example.com", null).send("ada@example.com", EMAIL))
                .isInstanceOf(DeliveryException.class).hasMessage("Could not send by EMAIL")
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isTrue());

        SnsClient sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class)))
                .thenThrow(ThrottledException.builder().statusCode(400).build())
                .thenThrow(InvalidParameterException.builder().statusCode(400).build());
        Senders.Sns sender = new Senders.Sns(sns, null, null);
        assertThatThrownBy(() -> sender.send("+15555550100", SMS))
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isFalse());
        assertThatThrownBy(() -> sender.send("+15555550100", SMS))
                .hasMessage("Could not send by SMS").satisfies(e -> assertThat(((DeliveryException) e).refused()).isTrue());

        assertThat(DeliveryException.clearlyRefused(SdkClientException.create("timeout"))).isFalse();
        assertThat(DeliveryException.clearlyRefused(AwsServiceException.builder().statusCode(500).build())).isFalse();
        assertThat(DeliveryException.clearlyRefused(AwsServiceException.builder().statusCode(302).build())).isFalse();
        assertThat(DeliveryException.clearlyRefused(AwsServiceException.builder().statusCode(429).build())).isFalse();
        assertThat(DeliveryException.clearlyRefused(AwsServiceException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("LimitExceededException").build()).build())).isFalse();
        assertThat(DeliveryException.clearlyRefused(AwsServiceException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidParameter").build()).build())).isTrue();
        assertThat(DeliveryException.clearlyRefused(AwsServiceException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().build()).build())).isTrue();
    }

    static JavaMailSender mail() {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        return mail;
    }

    @Test
    void smtpSendsAPlainTextUtf8Email() throws Exception {
        JavaMailSender mail = mail();
        new Senders.Smtp(mail, "alerts@creditagent.local", TEN_SECONDS).send("ada@example.com", EMAIL);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(message.getFrom()).extracting(Address::toString).containsExactly("alerts@creditagent.local");
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly("ada@example.com");
        assertThat(message.getSubject()).isEqualTo(EMAIL.subject());
        assertThat(message.getContent()).isEqualTo(EMAIL.text());
        message.saveChanges();
        assertThat(message.getContentType()).contains("text/plain").containsIgnoringCase("UTF-8");
    }

    @Test
    void smtpRejectionsOfTheRecipientAre422AndTransientFailuresAre503() throws Exception {
        Address[] ada = {new InternetAddress("ada@example.com")};
        Address[] none = {};
        MailSendException rejected = new MailSendException(Map.of(new Object(),
                new SendFailedException("550 no such user", null, none, none, ada)));
        MailSendException later = new MailSendException(Map.of(new Object(),
                new SendFailedException("451 try later", null, none, ada, none)));
        MailSendException connection = new MailSendException("Mail server connection failed",
                new jakarta.mail.MessagingException("connect timed out"));

        assertThat(DeliveryException.mailRefused(rejected)).isTrue();
        assertThat(DeliveryException.mailRefused(later)).isFalse();
        assertThat(DeliveryException.mailRefused(connection)).isFalse();
        assertThat(DeliveryException.mailRefused(new MailSendException(Map.of(new Object(),
                new jakarta.mail.MessagingException("other"))))).isFalse();
        assertThat(DeliveryException.mailRefused(new MailSendException(Map.of(new Object(),
                new SendFailedException("odd", null, null, null, null))))).isFalse();
        assertThat(DeliveryException.mailRefused(new MailSendException(Map.of(new Object(),
                new SendFailedException("550", null, null, null, ada))))).isTrue();
        assertThat(DeliveryException.mailRefused(new MailSendException(Map.of(new Object(),
                new SendFailedException("partial", null, ada, null, ada))))).isFalse();
        assertThat(DeliveryException.mailRefused(new MailAuthenticationException("bad login"))).isFalse();
        assertThat(DeliveryException.mailRefused(new MailParseException("bad address"))).isTrue();

        JavaMailSender mail = mail();
        doThrow(rejected).doThrow(connection).when(mail).send(any(MimeMessage.class));
        Senders.Smtp smtp = new Senders.Smtp(mail, "alerts@creditagent.local", TEN_SECONDS);
        assertThatThrownBy(() -> smtp.send("ada@example.com", EMAIL))
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isTrue());
        assertThatThrownBy(() -> smtp.send("ada@example.com", EMAIL))
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isFalse());
    }

    @Test
    void aSlowRelayIsGivenUpAfterTheTotalTimeoutAsAnUnknownOutcome() {
        JavaMailSender mail = mail();
        org.mockito.Mockito.doAnswer(i -> {
            Thread.sleep(5_000);
            return null;
        }).when(mail).send(any(MimeMessage.class));
        long start = System.nanoTime();
        assertThatThrownBy(() -> new Senders.Smtp(mail, "alerts@creditagent.local", java.time.Duration.ofMillis(200))
                .send("ada@example.com", EMAIL))
                .isInstanceOf(DeliveryException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class)
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isFalse());
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    @Test
    void anUnexpectedSmtpFailureOrAnInterruptIsUnknown() throws Exception {
        JavaMailSender mail = mail();
        doThrow(new IllegalStateException("bug")).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> new Senders.Smtp(mail, "alerts@creditagent.local", TEN_SECONDS).send("ada@example.com", EMAIL))
                .isInstanceOf(DeliveryException.class).hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isFalse());

        JavaMailSender slow = mail();
        org.mockito.Mockito.doAnswer(i -> {
            Thread.sleep(5_000);
            return null;
        }).when(slow).send(any(MimeMessage.class));
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean stillInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                new Senders.Smtp(slow, "alerts@creditagent.local", TEN_SECONDS).send("ada@example.com", EMAIL);
            } catch (Throwable t) {
                thrown.set(t);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(200);
        caller.interrupt();
        caller.join(2_000);
        assertThat(thrown.get()).isInstanceOf(DeliveryException.class).hasCauseInstanceOf(InterruptedException.class);
        assertThat(stillInterrupted).isTrue();
    }

    @Test
    void anEmailThatCannotBeBuiltIsRefused() {
        JavaMailSender mail = mail();
        assertThatThrownBy(() -> new Senders.Smtp(mail, "not an address <", TEN_SECONDS).send("ada@example.com", EMAIL))
                .isInstanceOf(DeliveryException.class)
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isTrue());
    }

    @Test
    void theLogSenderNeverWritesTheCodeTheTextOrTheFullDestination(CapturedOutput output) {
        String id = new Senders.Logging(Channel.SMS).send("+15555550100", SMS);
        new Senders.Logging(Channel.EMAIL).send("ada@example.com", EMAIL);
        assertThat(id).startsWith("log-");
        assertThat(output.getAll()).contains("+1******0100").contains("a***@example.com").contains("development only")
                .contains("Your CreditAgent sign-in code")
                .doesNotContain("042917").doesNotContain("5555550100").doesNotContain("ada@example.com");
    }

    @Test
    void withSmsLogCodesTheTextAndItsCodeAreLoggedButNotTheFullNumber(CapturedOutput output) {
        new Senders.Logging(Channel.SMS, true).send("+15555550100", SMS);
        assertThat(output.getAll()).contains("NOTIFICATIONS_SMS_LOG_CODES=true").contains("042917")
                .contains("+1******0100").doesNotContain("5555550100");
    }
}
