package com.creditagent.web.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OtpChannelAndPropertiesTest {

    @Test
    void channelsParseInAnyCaseAndRefuseTheRest() {
        assertThat(OtpChannel.parse("email")).contains(OtpChannel.EMAIL);
        assertThat(OtpChannel.parse(" Sms ")).contains(OtpChannel.SMS);
        assertThat(OtpChannel.parse("voice")).isEmpty();
        assertThat(OtpChannel.parse("")).isEmpty();
        assertThat(OtpChannel.parse(null)).isEmpty();
    }

    @Test
    void destinationsAreMasked() {
        assertThat(OtpChannel.EMAIL.mask("avery@example.com")).isEqualTo("a***@example.com");
        assertThat(OtpChannel.EMAIL.mask("@nobody")).isEqualTo("***");
        assertThat(OtpChannel.EMAIL.mask("no-at-sign")).isEqualTo("***");
        assertThat(OtpChannel.SMS.mask("+12065550100")).isEqualTo("***-***-0100");
        assertThat(OtpChannel.SMS.mask("+447700900123")).isEqualTo("***-***-0123");
        assertThat(OtpChannel.SMS.mask("+12")).isEqualTo("***-***-****");
        assertThat(OtpChannel.SMS.mask(null)).isEmpty();
        assertThat(OtpChannel.EMAIL.mask("")).isEmpty();
    }

    @Test
    void propertiesDefaultToSafeLimits() {
        OtpProperties p = new OtpProperties("t", null, null, null, null, null, null);
        assertThat(p.codeTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.challengeTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.maxAttempts()).isEqualTo(5);
        assertThat(p.maxSends()).isEqualTo(3);
        assertThat(p.resendCooldown()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.maxSendsPerUserPerHour()).isEqualTo(10);
    }

    @Test
    void propertiesRefuseLimitsThatDisableTheChecks() {
        assertThatThrownBy(() -> new OtpProperties("t", null, null, 0, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpProperties("t", null, null, null, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpProperties("t", null, null, null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpProperties("t", Duration.ofMinutes(20), Duration.ofMinutes(10), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code-ttl");
    }

    @Test
    void aDeliveryFailureKeepsItsClassWhenItGainsAWait() {
        OtpDeliveryException later = new OtpDeliveryException(OtpChannel.SMS, new IllegalStateException("x"), false)
                .withRetryAfter(Duration.ofSeconds(30));
        assertThat(later.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(later.channel()).isEqualTo(OtpChannel.SMS);
        assertThat(later.refused()).isFalse();
        assertThat(later.lastSend()).isFalse();
        OtpDeliveryException last = new OtpDeliveryException(OtpChannel.EMAIL, new IllegalStateException("x"), true).asLastSend();
        assertThat(last.lastSend()).isTrue();
        assertThat(last.refused()).isTrue();
        assertThat(last.retryAfter()).isNull();
        assertThat(last.getMessage()).isEqualTo("Could not send a sign-in code by EMAIL");
    }
}
