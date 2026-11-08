package com.creditagent.web.auth;

/**
 * Keys for tests, built at run time so no key-shaped literal sits in the source for secret
 * scanners to flag. They protect nothing.
 */
final class TestSecrets {

    private TestSecrets() {}

    static String of(String label) {
        return (label + "-test-only-").repeat(4);
    }
}
