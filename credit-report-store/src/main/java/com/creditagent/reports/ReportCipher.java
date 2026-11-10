package com.creditagent.reports;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts each stored credit report with AES-256-GCM (authenticated encryption).
 *
 * <ul>
 *   <li>Keys come from secrets of at least {@value #MIN_SECRET_BYTES} bytes ({@code openssl rand -hex 32}),
 *       each turned into an AES-256 key with HMAC-SHA256, so the same secret never serves two purposes.
 *   <li>Every encryption uses a fresh random 12-byte nonce; the stored value is {@code nonce || ciphertext+tag}.
 *   <li>The row's key (SSN hash and bureau) is the associated data: a ciphertext copied into another row
 *       fails to decrypt instead of showing one consumer's report as another's.
 *   <li>Each value records the id of the key that wrote it. New writes use the current key; the previous
 *       keys still decrypt, so a key can be rotated without rewriting the table first.
 * </ul>
 */
public final class ReportCipher {

    public static final int MIN_SECRET_BYTES = 32;
    static final String TRANSFORMATION = "AES/GCM/NoPadding";
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;

    /** Thrown when a stored report cannot be decrypted: unknown key, or tampered with or moved. */
    public static final class DecryptionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** An encrypted value and the id of the key that made it. */
    public record Sealed(String keyId, byte[] bytes) {}

    private record Key(String id, SecretKeySpec spec) {}

    private final Key current;
    private final Map<String, Key> byId = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * @param currentSecret encrypts new reports
     * @param previousSecrets still decrypt reports written before a rotation; may be empty
     */
    public ReportCipher(String currentSecret, List<String> previousSecrets) {
        this.current = key(currentSecret);
        byId.put(current.id(), current);
        for (String secret : previousSecrets == null ? List.<String>of() : previousSecrets) {
            if (secret != null && !secret.isBlank()) {
                Key key = key(secret.strip());
                byId.putIfAbsent(key.id(), key);
            }
        }
    }

    public String currentKeyId() {
        return current.id();
    }

    public Sealed encrypt(byte[] plaintext, String associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, current.spec(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] out = Arrays.copyOf(nonce, NONCE_BYTES + sealed.length);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return new Sealed(current.id(), out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        }
    }

    public byte[] decrypt(Sealed sealed, String associatedData) {
        Key key = byId.get(sealed.keyId());
        if (key == null) {
            throw new DecryptionException("Credit report was encrypted with an unknown key", null);
        }
        byte[] bytes = sealed.bytes();
        if (bytes.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new DecryptionException("Credit report ciphertext is too short", null);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key.spec(), new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new DecryptionException("Credit report failed its integrity check", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        }
    }

    private static Key key(String secret) {
        Objects.requireNonNull(secret, "CREDIT_REPORT_ENCRYPTION_KEY is required");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("Credit report encryption keys must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        byte[] aes = hmac(bytes, "CreditReports AES-256-GCM key v1");
        String id = HexFormat.of().formatHex(hmac(bytes, "CreditReports key id v1"), 0, 8);
        return new Key(id, new SecretKeySpec(aes, "AES"));
    }

    private static byte[] hmac(byte[] key, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
