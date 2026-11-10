package com.creditagent.advisor.guard;

import java.util.regex.Pattern;

/**
 * Removes personal identifiers from text before it is stored, sent to a model or shown (OWASP LLM02,
 * sensitive information disclosure): Social Security Numbers, card and account numbers, email
 * addresses and phone numbers. Masked forms such as {@code ***-**-6789} are left alone.
 */
public final class PiiRedactor {

    static final String SSN = "[SSN removed]";
    static final String NUMBER = "[number removed]";
    static final String EMAIL = "[email removed]";
    static final String PHONE = "[phone removed]";

    private static final Pattern SSN_PATTERN = Pattern.compile("(?<![\\d*])\\d{3}[- .]?\\d{2}[- .]?\\d{4}(?!\\d)");
    /** 12 to 19 digits, optionally grouped by spaces or dashes: card and account numbers. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){11,18}(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\d*])(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}(?!\\d)");

    private PiiRedactor() {}

    public static String redact(String text) {
        String result = LONG_NUMBER.matcher(text).replaceAll(NUMBER);
        result = PHONE_PATTERN.matcher(result).replaceAll(PHONE);
        result = SSN_PATTERN.matcher(result).replaceAll(SSN);
        return EMAIL_PATTERN.matcher(result).replaceAll(EMAIL);
    }
}
