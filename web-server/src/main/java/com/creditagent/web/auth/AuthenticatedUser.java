package com.creditagent.web.auth;

/**
 * The caller of an authenticated request, put on the request by {@link AuthenticationFilter}.
 *
 * @param ssnHash keyed hash of the user's own SSN (see {@link SsnHasher})
 * @param ssnLastFour last four digits of the user's SSN, for display
 */
public record AuthenticatedUser(String userId, String sessionId, String ssnHash, String ssnLastFour) {

    public static final String REQUEST_ATTRIBUTE = "com.creditagent.web.auth.AuthenticatedUser";

    public String ssnMasked() {
        return ssnLastFour == null ? null : "***-**-" + ssnLastFour;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + "]";
    }
}
