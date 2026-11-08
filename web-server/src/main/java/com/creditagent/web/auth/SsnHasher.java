package com.creditagent.web.auth;

import com.creditagent.common.Ssn;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed hash of an SSN, so the users table can tie an account to its SSN without storing the SSN.
 * A plain hash would not do: the SSN space (about 10^9) is small enough to brute-force. With
 * HMAC-SHA256 and a secret key, a copy of the table alone reveals nothing.
 */
public final class SsnHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SsnHasher(String secret) {
        byte[] bytes = Objects.requireNonNull(secret, "SSN HMAC key is required").getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("SSN HMAC key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    public String hash(Ssn ssn) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(ssn.digits().getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Constant-time comparison of {@code ssn}'s hash with a stored one. */
    public boolean matches(Ssn ssn, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(ssn).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
