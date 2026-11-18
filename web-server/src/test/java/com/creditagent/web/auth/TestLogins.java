package com.creditagent.web.auth;

/** Service-level tests that need a session: the password step, then a session as if the code was right. */
public final class TestLogins {

    private TestLogins() {}

    /** {@link AuthService#checkCredentials} then {@link AuthService#openSession}, skipping the code step. */
    public static AuthService.LoginResult login(AuthService auth, String userId, String password) {
        return auth.openSession(auth.checkCredentials(userId, password).userId());
    }
}
