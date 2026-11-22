package com.creditagent.web.auth;

/**
 * Paid subscriptions as registration and login see them (the subscriptions package). Neither method
 * may fail its caller: a registration or login goes ahead whatever happens here.
 */
public interface AccountSubscriptions {

    /** @return a JSON-serialisable view of the user's subscriptions and what to offer them, at login */
    Object atLogin(String userId);

    /**
     * The user ticked ID Protection on the registration form: start buying it. Registration has already
     * succeeded; a payment that cannot be taken leaves a free account.
     *
     * @return a JSON-serialisable outcome (started, or a checkout URL to pay at, or why not)
     */
    Object atRegistration(String userId);
}
