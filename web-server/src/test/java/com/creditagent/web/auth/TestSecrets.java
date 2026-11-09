package com.creditagent.web.auth;

/**
 * Keys for tests, built at run time so no key-shaped literal sits in the source for secret
 * scanners to flag. They protect nothing.
 */
public final class TestSecrets {

    private TestSecrets() {}

    public static String of(String label) {
        return (label + "-test-only-").repeat(4);
    }
}
