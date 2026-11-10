package com.creditagent.notifier;

import java.util.Objects;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends one plain-text email. */
@FunctionalInterface
public interface EmailSender {

    /** @throws MailException or another runtime exception if the email was not accepted for delivery */
    void send(String to, String subject, String body);

    /** False for the placeholder: nothing is sent, so nothing may be marked as notified. */
    default boolean configured() {
        return true;
    }

    /** Through an SMTP relay ({@code spring.mail.*}), with the connection and read timeouts set there. */
    static EmailSender smtp(JavaMailSender mail, String from) {
        Objects.requireNonNull(mail);
        Objects.requireNonNull(from);
        return (to, subject, body) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mail.send(message);
        };
    }

    /**
     * The placeholder when no mail relay is configured: refuses to send, and says it is not configured,
     * so the notifier leaves every alert unclaimed until a relay is set up.
     */
    static EmailSender placeholder() {
        return new EmailSender() {
            @Override
            public void send(String to, String subject, String body) {
                throw new IllegalStateException("No mail relay configured (MAIL_HOST)");
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }
}
