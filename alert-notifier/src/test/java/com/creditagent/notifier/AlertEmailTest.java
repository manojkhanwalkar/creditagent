package com.creditagent.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.creditagent.alerts.Alert;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class AlertEmailTest {

    @Test
    void theEmailSaysWhatWasFoundAndWhatToDoButNotWhere() {
        String body = AlertEmail.body(List.of(new Alert("ada@example.com", "breach-forum-beta", Instant.parse("2026-11-11T09:00:00Z"))), "");
        assertThat(body).contains("1 place").contains("first seen 2026-11-11").contains("identity protection")
                .contains("Sign in to Credit Agent to see the details").contains("never ask for your password")
                .doesNotContain("breach-forum-beta").doesNotContain("ada@example.com");
    }

    @Test
    void smtpSendsAPlainTextMessageFromTheConfiguredSender() {
        JavaMailSender mail = mock(JavaMailSender.class);
        EmailSender.smtp(mail, "alerts@credit.example").send("ada@example.com", "subject", "body");
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("alerts@credit.example");
        assertThat(message.getValue().getTo()).containsExactly("ada@example.com");
        assertThat(message.getValue().getText()).isEqualTo("body");
        EmailSender.placeholder().send("ada@example.com", "subject", "body"); // sends nothing, throws nothing
    }
}
