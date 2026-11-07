package com.creditagent.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Maps an SSN to the name of its credit file.
 *
 * <p>The file name is {@code HMAC-SHA256(key, ssn)} in hex plus {@code .json}. Using a keyed hash
 * (rather than the SSN, or a plain hash that can be brute-forced over the ~10^9 SSN space) means a
 * directory listing reveals nothing, and the name can only contain {@code [0-9a-f]}, which rules out
 * path traversal by construction.
 *
 * <p>The credit report generator and the MCP server must be configured with the same key.
 */
public final class CreditFileNames {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKeySpec key;

    public CreditFileNames(String secret) {
        Objects.requireNonNull(secret, "HMAC key is required");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("HMAC key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    public String fileNameFor(Ssn ssn) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(ssn.digits().getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest) + ".json";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
