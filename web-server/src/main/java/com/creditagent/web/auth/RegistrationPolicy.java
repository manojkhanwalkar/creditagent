package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.InvalidRegistrationException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for user ids, passwords and contact details, following NIST SP 800-63B and the OWASP
 * Authentication Cheat Sheet: length over composition rules, a block list of common passwords, no
 * password that contains the user id, and no upper limit below 64 characters.
 */
public final class RegistrationPolicy {

    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 64;
    /** BCrypt only reads the first 72 bytes; refuse anything longer instead of silently truncating. */
    static final int MAX_PASSWORD_BYTES = 72;

    private static final Pattern USER_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    /** E.164: a plus sign, a country code and up to 15 digits in all. */
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** A short list of the most common passwords that pass the length rule. */
    private static final Set<String> COMMON = Set.of(
            "123456789012", "1234567890123", "12345678901234", "password1234", "password12345",
            "passwordpassword", "qwertyuiopas", "qwerty123456", "iloveyou1234", "letmein12345",
            "welcome12345", "administrator", "aaaaaaaaaaaa", "abcdefghijkl", "abc123abc123",
            "changeme1234", "football1234", "baseball1234", "monkey123456", "sunshine1234",
            "trustno1trustno1", "creditagent1", "creditscore1", "p@ssw0rd1234", "passw0rd1234");

    private RegistrationPolicy() {}

    /** The stored form of a user id (trimmed, lower case), without validating it. Used by login. */
    public static String canonicalUserId(String userId) {
        return userId == null ? "" : userId.strip().toLowerCase(Locale.ROOT);
    }

    /** Lower-cases and validates a user id. */
    public static String normaliseUserId(String userId) {
        String id = canonicalUserId(userId);
        if (!USER_ID.matcher(id).matches()) {
            throw new InvalidRegistrationException(
                    "User id must be 3 to 32 characters: letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        return id;
    }

    public static void checkPassword(String password, String userId) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRegistrationException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRegistrationException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            throw new InvalidRegistrationException("That password is too common. Choose another.");
        }
        if (lower.chars().distinct().count() < 4) {
            throw new InvalidRegistrationException("Password must use at least 4 different characters.");
        }
        if (userId != null && !userId.isEmpty() && lower.contains(userId.toLowerCase(Locale.ROOT))) {
            throw new InvalidRegistrationException("Password must not contain the user id.");
        }
    }

    public static String normaliseEmail(String email) {
        String value = email == null ? "" : email.strip();
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) {
            throw new InvalidRegistrationException("Enter a valid email address.");
        }
        return value;
    }

    /** A US ZIP code; ZIP+4 is accepted and stored as its first five digits. */
    public static String normaliseZip(String zip) {
        String value = zip == null ? "" : zip.strip();
        if (!value.matches("\\d{5}(-\\d{4})?")) {
            throw new InvalidRegistrationException("Enter your 5-digit ZIP code.");
        }
        return value.substring(0, 5);
    }

    /** Accepts spaces, dashes, dots and brackets as separators and stores E.164. */
    public static String normalisePhone(String phone) {
        String value = phone == null ? "" : phone.strip().replaceAll("[\\s().-]", "");
        if (!PHONE.matcher(value).matches()) {
            throw new InvalidRegistrationException("Enter the phone number in international form, e.g. +1 415 555 0100.");
        }
        return value;
    }
}
