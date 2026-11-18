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
        OtpProperties p = new OtpProperties(null, null, null, null, "no-reply@x.example", null, null, null, null, "t",
                null, null, null, null, null, null);
        assertThat(p.delivery()).isEqualTo(OtpProperties.Delivery.AWS);
        assertThat(p.region()).isEqualTo("us-east-1");
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.codeTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.challengeTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.maxAttempts()).isEqualTo(5);
        assertThat(p.maxSends()).isEqualTo(3);
        assertThat(p.resendCooldown()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.maxSendsPerUserPerHour()).isEqualTo(10);
    }

    @Test
    void propertiesRefuseLimitsThatDisableTheChecks() {
        assertThatThrownBy(() -> new OtpProperties(null, " ", null, null, "f", null, null, null, null, "t",
                null, null, 0, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpProperties(null, null, null, null, "f", null, null, null, null, "t",
                null, null, null, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpProperties(null, null, null, null, "f", null, null, null, null, "t",
                null, null, null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtpProperties(null, null, null, null, "f", null, null, null, null, "t",
                Duration.ofMinutes(20), Duration.ofMinutes(10), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code-ttl");
    }
}
