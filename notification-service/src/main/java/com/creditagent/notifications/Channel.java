package com.creditagent.notifications;

import com.creditagent.common.EmailAddress;
import java.util.regex.Pattern;

/** Where a message goes. */
public enum Channel {
    /** An email, through SES or an SMTP relay. */
    EMAIL,
    /** A text message to an E.164 mobile number, through SNS. */
    SMS;

    /** {@code +}, a country code that does not start with 0, and 7 to 15 digits in all. */
    static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

    /** The longest destination either channel takes. */
    static final int MAX_TO_LENGTH = EmailAddress.MAX_LENGTH;

    /** True when {@code to} is an address this channel can send to (the registration rule for email). */
    boolean accepts(String to) {
        return this == EMAIL ? EmailAddress.isValid(to) : to != null && E164.matcher(to).matches();
    }

    /**
     * Enough of a destination to recognise it, not enough to learn it: an email keeps its first
     * character and domain ({@code a***@example.com}), a number its country digit and last four
     * ({@code +1******0100}). This is the only form a destination is stored or logged in.
     */
    String mask(String to) {
        if (to == null || to.isEmpty()) {
            return "";
        }
        if (this == EMAIL) {
            int at = to.lastIndexOf('@');
            return at <= 0 ? "***" : to.charAt(0) + "***" + to.substring(at);
        }
        String digits = to.replaceAll("\\D", "");
        if (digits.length() < 6) {
            return "+" + "*".repeat(digits.length());
        }
        return "+" + digits.charAt(0) + "*".repeat(digits.length() - 5) + digits.substring(digits.length() - 4);
    }
}
