package com.creditagent.notifier;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends one plain-text email. */
@FunctionalInterface
public interface EmailSender {

    /** @throws MailException or another runtime exception if the email was not accepted for delivery */
    void send(String to, String subject, String body);

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
     * The placeholder when no mail relay is configured: sends nothing and logs that it would have,
     * without the address.
     */
    static EmailSender placeholder() {
        Logger log = LoggerFactory.getLogger(EmailSender.class);
        return (to, subject, body) -> log.info("No mail relay configured (MAIL_HOST): an alert email was not sent");
    }
}
