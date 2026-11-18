package com.creditagent.web.auth.otp;

import java.util.Locale;
import java.util.Optional;

/** Where a one-time sign-in code is sent. The user picks one at each login. */
public enum OtpChannel {
    /** To the account's email address, through Amazon SES. */
    EMAIL,
    /** As a text message to the account's mobile number, through Amazon SNS. */
    SMS;

    /** The channel named by {@code value} (any letter case), or empty if there is none. */
    public static Optional<OtpChannel> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String name = value.strip().toUpperCase(Locale.ROOT);
        for (OtpChannel channel : values()) {
            if (channel.name().equals(name)) {
                return Optional.of(channel);
            }
        }
        return Optional.empty();
    }

    /**
     * What the user sees for a destination: enough to recognise it, not enough to learn it. An email
     * keeps its first character and domain ({@code a***@example.com}); a phone number keeps its last
     * four digits ({@code ***-***-0100}).
     */
    public String mask(String destination) {
        if (destination == null || destination.isEmpty()) {
            return "";
        }
        if (this == EMAIL) {
            int at = destination.lastIndexOf('@');
            return at <= 0 ? "***" : destination.charAt(0) + "***" + destination.substring(at);
        }
        String digits = destination.replaceAll("\\D", "");
        return "***-***-" + (digits.length() >= 4 ? digits.substring(digits.length() - 4) : "****");
    }
}
