package com.creditagent.web.auth;

import java.time.Instant;

/**
 * A registered user. {@code userId} is stored lower case, so ids are unique regardless of case.
 * Email and phone are kept for password recovery, which is not built yet.
 */
public record User(String userId, String passwordHash, String email, String phone, Instant createdAt) {

    @Override
    public String toString() {
        return "User[userId=" + userId + ", passwordHash=<redacted>, email=<redacted>, phone=<redacted>]";
    }
}
