package com.creditagent.common;

import java.util.regex.Pattern;

/**
 * The one rule for an email address, shared by registration and the alerts look-up, so an address
 * the web server accepted is never refused by a service it is later sent to.
 */
public final class EmailAddress {

    /** A local part of 1–64 characters without {@code @} or spaces, and a dotted domain. */
    public static final String REGEX = "^[^@\\s]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$";
    public static final Pattern PATTERN = Pattern.compile(REGEX);
    public static final int MAX_LENGTH = 254;

    private EmailAddress() {}

    public static boolean isValid(String email) {
        return email != null && email.length() <= MAX_LENGTH && PATTERN.matcher(email).matches();
    }
}
