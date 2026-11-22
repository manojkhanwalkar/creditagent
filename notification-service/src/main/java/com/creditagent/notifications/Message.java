package com.creditagent.notifications;

/**
 * A rendered message. {@link #toString} hides the text, which can hold a sign-in code.
 *
 * @param subject the email subject; null for a text message
 * @param text the plain-text body
 */
public record Message(String subject, String text) {

    @Override
    public String toString() {
        return "Message[subject=" + subject + ", text=<redacted>]";
    }
}
