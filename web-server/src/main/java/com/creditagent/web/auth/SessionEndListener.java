package com.creditagent.web.auth;

/**
 * Told when a user logs out, so per-session state elsewhere (the chat's conversation memory) can be
 * dropped at once rather than when it times out. Called after the session is deleted; a listener
 * that fails must not fail the logout.
 */
@FunctionalInterface
public interface SessionEndListener {

    void sessionEnded(AuthenticatedUser user);
}
