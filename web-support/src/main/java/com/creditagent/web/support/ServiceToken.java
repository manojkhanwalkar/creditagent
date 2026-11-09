package com.creditagent.web.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The shared token internal services present to each other ({@code INTERNAL_API_TOKEN}) as
 * {@code Authorization: Bearer <token>}. Compared in constant time, and never printed.
 */
public final class ServiceToken {

    public static final int MIN_LENGTH = 32;
    private static final String BEARER = "Bearer ";

    private final byte[] token;

    /** @param token the configured token; blank means none is configured */
    public ServiceToken(String token) {
        if (token != null && !token.isBlank() && token.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("INTERNAL_API_TOKEN must be at least " + MIN_LENGTH + " characters");
        }
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return token != null;
    }

    /** True only if a token is configured and the header carries exactly it as a bearer token. */
    public boolean matches(String authorizationHeader) {
        if (token == null || authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        byte[] presented = authorizationHeader.substring(BEARER.length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    @Override
    public String toString() {
        return "ServiceToken[" + (isConfigured() ? "configured" : "none") + "]";
    }
}
