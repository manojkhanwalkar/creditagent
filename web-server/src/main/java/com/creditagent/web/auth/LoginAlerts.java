package com.creditagent.web.auth;

/**
 * Dark-web alerts to return with a successful login (the Alerts service). Must not fail the login:
 * when alerts cannot be checked, it returns a value that says so.
 */
@FunctionalInterface
public interface LoginAlerts {

    /** @return a JSON-serialisable view of the user's alerts */
    Object alertsFor(String userId);
}
