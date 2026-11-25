package com.creditagent.notifications;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/** The providers: SES and SMTP for email, SNS for SMS, and the development-only log. */
final class Senders {

    private Senders() {}

    /**
     * Email through Amazon SES (API v2). The client carries the timeouts and the SDK's standard retries
     * (see {@code NotificationConfiguration}); a retried send can at worst deliver the same message twice.
     */
    static final class Ses implements Sender {

        private final SesV2Client ses;
        private final String from;
        private final String configurationSet;

        /** @param configurationSet SES configuration set for delivery events, or null/blank for none */
        Ses(SesV2Client ses, String from, String configurationSet) {
            this.ses = Objects.requireNonNull(ses);
            this.from = Objects.requireNonNull(from);
            this.configurationSet = configurationSet == null || configurationSet.isBlank() ? null : configurationSet;
        }

        @Override
        public String send(String to, Message message) {
            SendEmailRequest request = SendEmailRequest.builder()
                    .fromEmailAddress(from)
                    .destination(d -> d.toAddresses(to))
                    .content(c -> c.simple(m -> m
                            .subject(utf8(message.subject()))
                            .body(b -> b.text(utf8(message.text())))))
                    .configurationSetName(configurationSet)
                    .build();
            try {
                return ses.sendEmail(request).messageId();
            } catch (SdkException e) {
                throw DeliveryException.fromAws(Channel.EMAIL, e);
            }
        }

        private static Content utf8(String data) {
            return Content.builder().data(data).charset(StandardCharsets.UTF_8.name()).build();
        }
    }

    /**
     * Texts through Amazon SNS, published straight to the number (no topic), marked {@code Transactional}
     * so SNS routes it for reliability and does not apply marketing opt-out rules.
     */
    static final class Sns implements Sender {

        static final String SMS_TYPE = "AWS.SNS.SMS.SMSType";
        static final String SENDER_ID = "AWS.SNS.SMS.SenderID";
        static final String ORIGINATION_NUMBER = "AWS.MM.SMS.OriginationNumber";

        private final SnsClient sns;
        private final Map<String, MessageAttributeValue> attributes;

        /**
         * @param senderId alphanumeric sender id where countries allow one, or null/blank
         * @param originationNumber the registered number to send from, or null/blank for the account default
         */
        Sns(SnsClient sns, String senderId, String originationNumber) {
            this.sns = Objects.requireNonNull(sns);
            Map<String, MessageAttributeValue> values = new HashMap<>();
            values.put(SMS_TYPE, string("Transactional"));
            if (senderId != null && !senderId.isBlank()) {
                values.put(SENDER_ID, string(senderId));
            }
            if (originationNumber != null && !originationNumber.isBlank()) {
                values.put(ORIGINATION_NUMBER, string(originationNumber));
            }
            this.attributes = Map.copyOf(values);
        }

        @Override
        public String send(String to, Message message) {
            PublishRequest request = PublishRequest.builder()
                    .phoneNumber(to)
                    .message(message.text())
                    .messageAttributes(attributes)
                    .build();
            try {
                return sns.publish(request).messageId();
            } catch (SdkException e) {
                throw DeliveryException.fromAws(Channel.SMS, e);
            }
        }

        private static MessageAttributeValue string(String value) {
            return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
        }
    }

    /**
     * Email through an SMTP relay ({@code spring.mail.*}). The mail timeouts bound each socket operation,
     * but a slow relay could chain several; so the whole send runs on a virtual thread and is given up
     * after {@code timeout} (an unknown outcome), keeping every send inside the lease and the shutdown phase.
     */
    static final class Smtp implements Sender {

        private final JavaMailSender mail;
        private final String from;
        private final java.time.Duration timeout;

        Smtp(JavaMailSender mail, String from, java.time.Duration timeout) {
            this.mail = Objects.requireNonNull(mail);
            this.from = Objects.requireNonNull(from);
            this.timeout = Objects.requireNonNull(timeout);
        }

        @Override
        public String send(String to, Message message) {
            MimeMessage mime;
            try {
                mime = mail.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(to);
                helper.setSubject(message.subject());
                helper.setText(message.text(), false);
            } catch (MessagingException e) {
                throw DeliveryException.fromMail(new MailPreparationException("Could not build the email", e));
            }
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(() -> {
                mail.send(mime);
                return mime.getMessageID();
            });
            Thread.ofVirtual().name("smtp-send").start(task);
            try {
                return task.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                task.cancel(true);
                throw new DeliveryException(Channel.EMAIL, e, false);
            } catch (InterruptedException e) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw new DeliveryException(Channel.EMAIL, e, false);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof MailException failure) {
                    throw DeliveryException.fromMail(failure);
                }
                throw new DeliveryException(Channel.EMAIL, e.getCause(), false);
            }
        }
    }

    /**
     * Local development only ({@code NOTIFICATIONS_EMAIL=log} or {@code NOTIFICATIONS_SMS=log}): sends
     * nothing and writes one line naming the template and the masked destination. Never the text: a
     * sign-in code in a log would let anyone who reads it sign in. Refused unless DynamoDB is local.
     *
     * <p>The one exception is {@code NOTIFICATIONS_SMS_LOG_CODES=true} ({@code showText}), for local
     * sign-in by text without SNS: the text, code included, is logged too, under the same local-only guard.
     */
    static final class Logging implements Sender {

        private static final Logger log = LoggerFactory.getLogger(Logging.class);

        private final Channel channel;
        private final boolean showText;

        Logging(Channel channel) {
            this(channel, false);
        }

        Logging(Channel channel, boolean showText) {
            this.channel = Objects.requireNonNull(channel);
            this.showText = showText;
        }

        @Override
        public String send(String to, Message message) {
            String id = "log-" + UUID.randomUUID();
            log.warn("[NOTIFICATIONS_{}=log, development only] not sent: {} to {} (id {})", channel, describe(message),
                    channel.mask(to), id);
            if (showText) {
                log.warn("[NOTIFICATIONS_SMS_LOG_CODES=true, local sign-in only] text {}: {}", id, message.text());
            }
            return id;
        }

        private static String describe(Message message) {
            return message.subject() == null ? "a text message" : "an email \"" + message.subject() + "\"";
        }
    }
}
