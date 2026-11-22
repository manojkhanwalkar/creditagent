package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

/** The templates' wording (identical to what web-server and alert-notifier send today) and every param check. */
class TemplateTest {

    /** web-server's OtpSender.text(code, 5 minutes, false), copied verbatim. */
    static final String TODAY_OTP_EMAIL = "Your CreditAgent sign-in code is 042917.\n\n"
            + "It expires in 5 minutes. Never share it: CreditAgent will never ask you for it.\n\n"
            + "If you did not just try to sign in, someone else knows your password. Change it, and do not"
            + " enter this code anywhere.\n";

    /** web-server's OtpSender.text(code, 5 minutes, true), copied verbatim. */
    static final String TODAY_OTP_SMS =
            "042917 is your CreditAgent sign-in code. It expires in 5 minutes. Never share it; we will never ask for it.";

    /** alert-notifier's AlertEmail.body for two alerts, first on 2026-11-09, with an app URL, copied verbatim. */
    static final String TODAY_ALERT = """
            Hello,

            Your ID Protection found your email address in 2 places on the dark web (first seen 2026-11-09). \
            Someone may try to use it to open accounts in your name or to get into yours.

            What we suggest:
            - Watch your credit report for accounts you did not open.
            - Change the passwords you use with this email address, and turn on two-step sign-in where you can.
            - Sign in to Credit Agent at https://app.creditagent.example to see the details.

            We will never ask for your password or SSN by email.

            Credit Agent
            """;

    static Map<String, String> otp(String code, String seconds) {
        return Map.of("code", code, "validSeconds", seconds);
    }

    @Test
    void theSignInCodeEmailAndTextMatchTodaysExactly() {
        Message email = Template.OTP_SIGN_IN.validateAndRender(otp("042917", "300"), Channel.EMAIL);
        assertThat(email.subject()).isEqualTo("Your CreditAgent sign-in code");
        assertThat(email.text()).isEqualTo(TODAY_OTP_EMAIL);
        Message sms = Template.OTP_SIGN_IN.validateAndRender(otp("042917", "300"), Channel.SMS);
        assertThat(sms.subject()).isNull();
        assertThat(sms.text()).isEqualTo(TODAY_OTP_SMS);
        assertThat(sms.toString()).doesNotContain("042917");
    }

    @Test
    void secondsAreExactNotRoundedToMinutes() {
        assertThat(Template.OTP_SIGN_IN.validateAndRender(otp("123456", "90"), Channel.SMS).text()).contains("expires in 90 seconds.");
        assertThat(Template.duration(60)).isEqualTo("1 minute");
        assertThat(Template.duration(1)).isEqualTo("1 second");
        assertThat(Template.duration(600)).isEqualTo("10 minutes");
    }

    @Test
    void theDarkWebAlertMatchesTodaysEmailExactly() {
        Message alert = Template.DARK_WEB_ALERT.validateAndRender(
                Map.of("count", "2", "firstSeen", "2026-11-09", "appUrl", "https://app.creditagent.example"), Channel.EMAIL);
        assertThat(alert.subject()).isEqualTo("Your email address was found on the dark web");
        assertThat(alert.text()).isEqualTo(TODAY_ALERT);

        Message single = Template.DARK_WEB_ALERT.validateAndRender(Map.of("count", "1", "firstSeen", "2026-11-09"), Channel.EMAIL);
        assertThat(single.text()).contains("in 1 place on the dark web").contains("- Sign in to Credit Agent to see the details.");
        assertThat(Template.DARK_WEB_ALERT.validateAndRender(
                Map.of("count", "1", "firstSeen", "2026-11-09", "appUrl", ""), Channel.EMAIL).text())
                .contains("- Sign in to Credit Agent to see the details.");
    }

    static java.util.stream.Stream<Arguments> invalid() {
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            tooMany.put("p" + i, "1");
        }
        return java.util.stream.Stream.of(
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, Map.of("code", "042917"), "needs the param validSeconds"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, Map.of(), "needs the param code"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, null, "needs the param code"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL,
                        Map.of("code", "042917", "validSeconds", "300", "text", "hi"), "takes only the params [code, validSeconds]"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("12345", "300"), "code must be 6 digits"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("1234567", "300"), "code must be 6 digits"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("12a456", "300"), "code must be 6 digits"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("123456", "0"), "validSeconds must be"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("123456", "86401"), "validSeconds must be"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("123456", "5m"), "validSeconds must be"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, otp("123456", "9999999999"), "validSeconds must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.SMS, Map.of("count", "1", "firstSeen", "2026-11-09"),
                        "DARK_WEB_ALERT cannot be sent by SMS"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL, Map.of("count", "0", "firstSeen", "2026-11-09"), "count must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL, Map.of("count", "-1", "firstSeen", "2026-11-09"), "count must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL, Map.of("count", "1", "firstSeen", "09/11/2026"), "firstSeen must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL,
                        Map.of("count", "1", "firstSeen", "2026-11-09", "appUrl", "javascript:alert(1)"), "appUrl must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL,
                        Map.of("count", "1", "firstSeen", "2026-11-09", "appUrl", "https://evil.example/ x"), "appUrl must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL,
                        Map.of("count", "1", "firstSeen", "2026-11-09", "appUrl", "https://user@evil.example"), "appUrl must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL,
                        Map.of("count", "1", "firstSeen", "2026-11-09", "appUrl", "https://%zz"), "appUrl must be"),
                Arguments.of(Template.DARK_WEB_ALERT, Channel.EMAIL,
                        Map.of("count", "1", "firstSeen", "2026-11-09", "appUrl", "https://" + "a".repeat(600) + ".example"),
                        "must be a string of at most 512"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL, tooMany, "too many params"),
                Arguments.of(Template.OTP_SIGN_IN, Channel.EMAIL,
                        Map.of("code", "123456", "x".repeat(40), "1"), "takes only the params"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void everyBadParamIsRefusedNamingTheFieldButNotTheValue(Template template, Channel channel, Map<String, String> params,
            String problem) {
        assertThatThrownBy(() -> template.validateAndRender(params, channel))
                .isInstanceOf(InvalidNotificationException.class)
                .hasMessageContaining(problem)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("12345").doesNotContain("evil"));
    }

    @Test
    void aNullParamValueIsRefused() {
        Map<String, String> params = new HashMap<>();
        params.put("code", null);
        params.put("validSeconds", "300");
        assertThatThrownBy(() -> Template.OTP_SIGN_IN.validateAndRender(params, Channel.EMAIL))
                .isInstanceOf(InvalidNotificationException.class);
    }
}
