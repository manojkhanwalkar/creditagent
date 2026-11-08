package com.creditagent.common;

/**
 * Maps an SSN to the name of its credit file.
 *
 * <p>The file name is {@code HMAC-SHA256(key, ssn)} in hex plus {@code .json} ({@link SsnHmac}).
 * Using a keyed hash (rather than the SSN, or a plain hash that can be brute-forced over the ~10^9
 * SSN space) means a directory listing reveals nothing, and the name can only contain
 * {@code [0-9a-f]}, which rules out path traversal by construction.
 *
 * <p>The credit report generator and the MCP server must be configured with the same key.
 */
public final class CreditFileNames {

    private final SsnHmac hmac;

    public CreditFileNames(String secret) {
        this.hmac = new SsnHmac(secret, "HMAC key");
    }

    public String fileNameFor(Ssn ssn) {
        return hmac.hex(ssn) + ".json";
    }
}
