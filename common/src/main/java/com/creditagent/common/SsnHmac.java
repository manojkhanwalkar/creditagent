package com.creditagent.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed hash (HMAC-SHA256, hex) of an SSN's nine digits. Used wherever an SSN must be recognised
 * without being stored: the credit file names ({@link CreditFileNames}) and the users table.
 *
 * <p>A plain hash would not do: the SSN space (about 10^9) is small enough to brute-force. With a
 * secret key of at least {@value #MIN_KEY_BYTES} bytes, a copy of the hashes alone reveals nothing.
 */
public final class SsnHmac {

    public static final int MIN_KEY_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    /**
     * @param secret the key, at least {@value #MIN_KEY_BYTES} bytes in UTF-8
     * @param name what the key is called, for the error message
     */
    public SsnHmac(String secret, String name) {
        Objects.requireNonNull(secret, name + " is required");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(name + " must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /** 64 lower-case hex characters. */
    public String hex(Ssn ssn) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(ssn.digits().getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Constant-time comparison of {@code ssn}'s hash with a stored one. */
    public boolean matches(Ssn ssn, String storedHex) {
        if (storedHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hex(ssn).getBytes(StandardCharsets.US_ASCII), storedHex.getBytes(StandardCharsets.US_ASCII));
    }
}
