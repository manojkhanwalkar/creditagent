package com.creditagent.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The real context: without a mail relay the placeholder sends nothing; with one, SMTP is used. */
class NotifierWiringTest {

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"credit.notifier.cron=-", "credit.notifier.dynamodb-endpoint=http://localhost:1",
        "spring.mail.host="})
    class WithoutARelay {
        @Autowired
        AlertNotifier notifier;

        @Autowired
        EmailSender email;

        @MockitoBean
        JavaMailSender mail;

        @Test
        void nothingIsSent() {
            assertThat(email.configured()).isFalse();
            assertThat(notifier.run().skipped()).isTrue(); // reads and claims nothing
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> email.send("ada@example.com", "s", "b"))
                    .isInstanceOf(IllegalStateException.class);
            verify(mail, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"credit.notifier.cron=-", "credit.notifier.dynamodb-endpoint=http://localhost:1",
        "spring.mail.host=smtp.example", "spring.mail.properties.mail.smtp.starttls.required=true"})
    class WithARelay {
        @Autowired
        EmailSender email;

        @MockitoBean
        JavaMailSender mail;

        @Test
        void smtpIsUsed() {
            email.send("ada@example.com", "s", "b");
            verify(mail).send(any(SimpleMailMessage.class));
        }
    }
}
