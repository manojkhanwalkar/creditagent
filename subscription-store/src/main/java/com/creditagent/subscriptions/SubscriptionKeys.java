package com.creditagent.subscriptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** How the table names an email: a SHA-256 of the trimmed, lower-cased address, never the address. */
public final class SubscriptionKeys {

    /** Prefix of an email record's partition key; user ids cannot contain {@code #}, so no clash. */
    public static final String EMAIL_PREFIX = "email#";

    private SubscriptionKeys() {}

    public static String emailKey(String email) {
        String normalised = Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return EMAIL_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
