package com.creditagent.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** The real context: the notification-service once the token is set; without it, the placeholder sends nothing. */
class NotifierWiringTest {

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"credit.notifier.cron=-", "credit.notifier.dynamodb-endpoint=http://localhost:1",
        "credit.internal.token="})
    class WithoutTheToken {
        @Autowired
        AlertNotifier notifier;

        @Autowired
        EmailSender email;

        @Test
        void nothingIsSent() {
            assertThat(email.configured()).isFalse();
            assertThat(notifier.run().skipped()).isTrue(); // reads and claims nothing
            assertThatThrownBy(() -> email.sendAlert("ada@example.com", List.of(), Instant.EPOCH, ""))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_API_TOKEN");
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"credit.notifier.cron=-", "credit.notifier.dynamodb-endpoint=http://localhost:1",
        "credit.notifications.url=http://notification-service:8094",
        "credit.internal.token=internal-token-for-tests-only-internal-token"})
    class WithTheUrlAndToken {
        @Autowired
        EmailSender email;

        @Autowired
        NotificationsProperties notifications;

        @Autowired
        ApplicationContext context;

        @Test
        void theNotificationServiceIsUsedAndNoMailRelayIsBuilt() {
            assertThat(email).isInstanceOf(RemoteEmailSender.class);
            assertThat(email.configured()).isTrue();
            assertThat(notifications.timeout()).isEqualTo(Duration.ofSeconds(15));
            // No JavaMailSender and no mail health check: SMTP is gone from this service.
            assertThat(context.getBeanNamesForType(Object.class)).noneMatch(name -> name.contains("Mail") || name.startsWith("mail"));
        }
    }

    @Test
    void theServiceDefaultsAreLocalAndBounded() {
        NotificationsProperties defaults = new NotificationsProperties(null, null);
        assertThat(defaults.url()).isEqualTo("http://localhost:8094");
        assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(() -> new NotificationsProperties(" ", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theServiceUrlMustBeHttpsUnlessLocal() {
        for (String ok : new String[] {"http://localhost:8094", "http://127.0.0.1:8094", "http://[::1]:8094",
                "http://notification-service:8094", "https://notifications.internal.example"}) {
            assertThat(new NotificationsProperties(ok, null).url()).isEqualTo(ok);
        }
        for (String bad : new String[] {"http://notifications.internal.example", "http://10.0.0.5:8094", "ftp://localhost",
                "notification-service:8094", "/internal", "http://", "http://exa mple"}) {
            assertThatThrownBy(() -> new NotificationsProperties(bad, null)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOTIFICATIONS_URL");
        }
    }
}
