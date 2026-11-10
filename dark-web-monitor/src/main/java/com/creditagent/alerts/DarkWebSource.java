package com.creditagent.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Where exposures are looked for. A real source would read breach dumps and paste sites; that is out
 * of scope here, and {@link #placeholder} never connects anywhere.
 */
@FunctionalInterface
public interface DarkWebSource {

    /** Whether {@code email} (already normalised) appears at {@code location}. */
    boolean exposed(String location, String email);

    /**
     * A stand-in that contacts nothing: an address in {@code alwaysExposed} is found everywhere, and any
     * other is found at a location when a hash of the two falls under {@code rate}. Deterministic, so
     * the same address gives the same answer every cycle.
     */
    static DarkWebSource placeholder(Set<String> alwaysExposed, double rate) {
        return (location, email) -> alwaysExposed.contains(email) || fraction(location + "|" + email) < rate;
    }

    /** A stable number in [0, 1) from the text. */
    static double fraction(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            long bits = ((hash[0] & 0xffL) << 24) | ((hash[1] & 0xffL) << 16) | ((hash[2] & 0xffL) << 8) | (hash[3] & 0xffL);
            return bits / (double) (1L << 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
