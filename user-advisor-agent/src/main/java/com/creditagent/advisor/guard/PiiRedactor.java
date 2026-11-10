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

    /** Separated by any dash, a slash, a dot or spaces ("123 – 45 – 6789", "123/45/6789"), or not at all. */
    private static final Pattern SSN_PATTERN = Pattern.compile(
            "(?<![\\d*])\\d{3}(?:\\s*[\\p{Pd}/.]\\s*|\\s)?\\d{2}(?:\\s*[\\p{Pd}/.]\\s*|\\s)?\\d{4}(?!\\d)");
    /** 12 to 19 digits, optionally grouped by spaces, dots or dashes: card and account numbers. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d(?:[ .-]?\\d){11,18}(?!\\d)");
    /** 8 to 11 digits in one run, other than an SSN or phone (handled first): account numbers. */
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("(?<![\\d*])\\d{8}(?:\\d{3})?(?!\\d)|(?<![\\d*])\\d{11}(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** US numbers, with or without separators. */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\d*])(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?!\\d)(?!-\\d)");
    /** International numbers written with a leading "+"; matched first, before long-number grouping. */
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile("\\+\\d{1,3}(?:[ .-]?\\d{1,4}){2,5}(?!\\d)");

    private PiiRedactor() {}

    /** Digits of every script become ASCII, so Arabic-Indic or Devanagari numbers are matched too. */
    private static String normalise(String text) {
        StringBuilder ascii = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isDigit(cp) && cp > '9') {
                ascii.append((char) ('0' + Character.digit(cp, 10)));
            } else {
                ascii.appendCodePoint(cp);
            }
        });
        return ascii.toString();
    }

    public static String redact(String text) {
        String result = INTERNATIONAL_PHONE.matcher(normalise(text)).replaceAll(PHONE);
        result = LONG_NUMBER.matcher(result).replaceAll(NUMBER);
        result = PHONE_PATTERN.matcher(result).replaceAll(PHONE);
        result = SSN_PATTERN.matcher(result).replaceAll(SSN);
        result = ACCOUNT_NUMBER.matcher(result).replaceAll(NUMBER);
        return EMAIL_PATTERN.matcher(result).replaceAll(EMAIL);
    }
}
