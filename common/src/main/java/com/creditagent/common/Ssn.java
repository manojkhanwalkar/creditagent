package com.creditagent.common;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated US Social Security Number.
 *
 * <p>The raw digits are only reachable through {@link #digits()}. {@link #toString()} is masked so an
 * SSN that ends up in a log line, exception message or debugger view never shows more than the last
 * four digits.
 */
public final class Ssn {

    private static final Pattern FORMAT = Pattern.compile("^(\\d{3})-?(\\d{2})-?(\\d{4})$");

    private final String digits;

    private Ssn(String digits) {
        this.digits = digits;
    }

    /**
     * Parses {@code 123-45-6789} or {@code 123456789}. Surrounding whitespace is ignored.
     *
     * @throws InvalidSsnException if the value is not a structurally valid SSN. The message never
     *     contains the rejected value.
     */
    public static Ssn parse(String raw) {
        if (raw == null) {
            throw new InvalidSsnException("SSN is required");
        }
        var matcher = FORMAT.matcher(raw.strip());
        if (!matcher.matches()) {
            throw new InvalidSsnException("SSN must be 9 digits, optionally formatted as AAA-GG-SSSS");
        }
        String area = matcher.group(1);
        String group = matcher.group(2);
        String serial = matcher.group(3);
        // SSA never issues these ranges.
        if (area.equals("000") || area.equals("666") || area.charAt(0) == '9') {
            throw new InvalidSsnException("SSN area number is not valid");
        }
        if (group.equals("00")) {
            throw new InvalidSsnException("SSN group number is not valid");
        }
        if (serial.equals("0000")) {
            throw new InvalidSsnException("SSN serial number is not valid");
        }
        return new Ssn(area + group + serial);
    }

    public static boolean isValid(String raw) {
        try {
            parse(raw);
            return true;
        } catch (InvalidSsnException e) {
            return false;
        }
    }

    /** The nine raw digits. Handle with care: do not log or persist this value. */
    public String digits() {
        return digits;
    }

    /** Last four digits only, e.g. {@code ***-**-6789}. Safe for display and logs. */
    public String masked() {
        return "***-**-" + digits.substring(5);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ssn other && digits.equals(other.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits);
    }

    @Override
    public String toString() {
        return masked();
    }
}
