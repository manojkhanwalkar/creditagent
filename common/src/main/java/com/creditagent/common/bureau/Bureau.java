package com.creditagent.common.bureau;

/**
 * The three US nationwide credit bureaus. Each writes reports in its own format under
 * {@code <reports-root>/<directoryName>/}.
 */
public enum Bureau {
    EQUIFAX("Equifax"),
    EXPERIAN("Experian"),
    TRANSUNION("TransUnion");

    private final String directoryName;

    Bureau(String directoryName) {
        this.directoryName = directoryName;
    }

    /** Sub-directory of the reports root that holds this bureau's files, e.g. {@code Equifax}. */
    public String directoryName() {
        return directoryName;
    }
}
