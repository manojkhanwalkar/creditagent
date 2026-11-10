package com.creditagent.notifier;

import com.creditagent.alerts.Alert;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The email about a user's new dark-web findings. Plain text, fixed wording: no location names (they
 * stay behind sign-in) and nothing taken from outside the service but dates, so the email cannot carry
 * markup or links an attacker chose.
 */
public final class AlertEmail {

    public static final String SUBJECT = "Your email address was found on the dark web";

    private AlertEmail() {}

    public static String body(List<Alert> alerts, String appUrl) {
        LocalDate first = alerts.stream().map(a -> LocalDate.ofInstant(a.timestamp(), ZoneOffset.UTC)).min(LocalDate::compareTo)
                .orElseThrow();
        String where = alerts.size() == 1 ? "1 place" : alerts.size() + " places";
        String signIn = appUrl.isBlank() ? "Sign in to Credit Agent" : "Sign in to Credit Agent at " + appUrl;
        return """
                Hello,

                Our dark web monitoring found your email address in %s on the dark web (first seen %s). \
                Someone may try to use it to open accounts in your name or to get into yours.

                What we suggest:
                - Consider buying identity protection, which monitors your credit and helps restore your identity if it is misused.
                - Change the passwords you use with this email address, and turn on two-step sign-in where you can.
                - %s to see the details.

                We will never ask for your password or SSN by email.

                Credit Agent
                """.formatted(where, first, signIn);
    }
}
