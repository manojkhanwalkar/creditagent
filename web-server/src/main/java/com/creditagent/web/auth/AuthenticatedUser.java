package com.creditagent.web.auth;

/** The caller of an authenticated request, put on the request by {@link AuthenticationFilter}. */
public record AuthenticatedUser(String userId, String sessionId) {

    public static final String REQUEST_ATTRIBUTE = "com.creditagent.web.auth.AuthenticatedUser";
}
