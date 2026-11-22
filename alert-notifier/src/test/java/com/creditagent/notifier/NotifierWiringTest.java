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
            assertThat(email).isNotInstanceOf(RemoteEmailSender.class); // local is the default mode
            email.send("ada@example.com", "s", "b");
            verify(mail).send(any(SimpleMailMessage.class));
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"credit.notifier.cron=-", "credit.notifier.dynamodb-endpoint=http://localhost:1",
        "spring.mail.host=", "credit.notifications.mode=remote", "credit.notifications.url=http://notification-service:8094",
        "credit.internal.token=internal-token-for-tests-only-internal-token"})
    class InRemoteMode {
        @Autowired
        EmailSender email;

        @Autowired
        NotificationsProperties notifications;

        @Test
        void theNotificationServiceIsUsedAndCountsAsConfigured() {
            assertThat(email).isInstanceOf(RemoteEmailSender.class);
            assertThat(email.configured()).isTrue();
            assertThat(notifications.timeout()).isEqualTo(java.time.Duration.ofSeconds(15));
        }
    }

    @Test
    void localIsTheDefaultMode() {
        NotificationsProperties defaults = new NotificationsProperties(null, null, null);
        assertThat(defaults.mode()).isEqualTo(NotificationsProperties.Mode.LOCAL);
        assertThat(defaults.url()).isEqualTo("http://localhost:8094");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NotificationsProperties(null, " ", java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theServiceUrlMustBeHttpsUnlessLocal() {
        for (String ok : new String[] {"http://localhost:8094", "http://127.0.0.1:8094", "http://[::1]:8094",
                "http://notification-service:8094", "https://notifications.internal.example"}) {
            assertThat(new NotificationsProperties(null, ok, null).url()).isEqualTo(ok);
        }
        for (String bad : new String[] {"http://notifications.internal.example", "http://10.0.0.5:8094", "ftp://localhost",
                "notification-service:8094", "/internal", "http://", "http://exa mple"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NotificationsProperties(null, bad, null)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOTIFICATIONS_URL");
        }
    }
}
