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
            assertThat(notifier).isNotNull();
            email.send("ada@example.com", "s", "b");
            verify(mail, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"credit.notifier.cron=-", "credit.notifier.dynamodb-endpoint=http://localhost:1",
        "spring.mail.host=smtp.example"})
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
