package com.creditagent.common.bureau;

/**
 * The three US nationwide credit bureaus. Each writes reports in its own format; the
 * {@code CreditReports} table holds one per bureau and consumer, keyed by {@link #displayName()}.
 */
public enum Bureau {
    EQUIFAX("Equifax"),
    EXPERIAN("Experian"),
    TRANSUNION("TransUnion");

    private final String displayName;

    Bureau(String displayName) {
        this.displayName = displayName;
    }

    /** The bureau's name, e.g. {@code Equifax}: the {@code bureau} column of the CreditReports table. */
    public String displayName() {
        return displayName;
    }
}
