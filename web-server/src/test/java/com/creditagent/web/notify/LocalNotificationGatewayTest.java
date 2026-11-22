package com.creditagent.web.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.web.auth.RecordingOtpSender;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.notify.NotificationGateway.Notification;
import com.creditagent.web.notify.NotificationGateway.Template;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalNotificationGatewayTest {

    final RecordingOtpSender email = new RecordingOtpSender();
    final RecordingOtpSender sms = new RecordingOtpSender();
    final LocalNotificationGateway gateway = new LocalNotificationGateway(Map.of(OtpChannel.EMAIL, email, OtpChannel.SMS, sms));

    @Test
    void aSignInCodeIsRenderedByTheChannelsSender() {
        gateway.send(new Notification(OtpChannel.EMAIL, "a@example.com", Template.OTP_SIGN_IN,
                Map.of("code", "042917", "validMinutes", "5"), "otp:c1:1"));
        assertThat(email.sent).containsExactly(new RecordingOtpSender.Sent("a@example.com", "042917", Duration.ofMinutes(5)));
        assertThat(sms.sent).isEmpty();
    }

    @Test
    void anUnknownTemplateAMissingParamOrAMissingSenderIsRefused() {
        assertThatThrownBy(() -> gateway.send(new Notification(OtpChannel.EMAIL, "a@example.com", Template.DARK_WEB_ALERT,
                Map.of(), "k"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DARK_WEB_ALERT");
        assertThatThrownBy(() -> gateway.send(new Notification(OtpChannel.SMS, "+12065550100", Template.OTP_SIGN_IN,
                Map.of("code", "042917"), "k"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("validMinutes");
        assertThat(email.sent.size() + sms.sent.size()).isZero();
        assertThatThrownBy(() -> new LocalNotificationGateway(Map.of(OtpChannel.EMAIL, email)))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("SMS");
    }

    @Test
    void toStringHidesTheCodeDestinationAndKey() {
        String text = new Notification(OtpChannel.SMS, "+12065550100", Template.OTP_SIGN_IN,
                Map.of("code", "042917", "validMinutes", "5"), "otp:secret-challenge:1").toString();
        assertThat(text).contains("OTP_SIGN_IN", "SMS", "code", "validMinutes")
                .doesNotContain("042917", "+12065550100", "secret-challenge");
    }
}
