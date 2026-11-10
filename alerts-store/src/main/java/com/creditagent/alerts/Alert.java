package com.creditagent.alerts;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One finding: an email address seen at one dark-web location. Stored once per email and location;
 * {@code timestamp} is when the monitor first found it there.
 *
 * @param email lower case, as {@link #normalise} leaves it
 * @param location the placeholder location id where it was found
 */
public record Alert(String email, String location, Instant timestamp) {

    public Alert {
        email = normalise(email);
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /** Lower case and trimmed, so the monitor and the alerts service agree on the key. */
    public static String normalise(String email) {
        return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "Alert[email=<redacted>, location=" + location + ", timestamp=" + timestamp + "]";
    }
}
