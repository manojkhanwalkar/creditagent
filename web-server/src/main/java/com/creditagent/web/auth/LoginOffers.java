package com.creditagent.web.auth;

/**
 * Card offers to return with a successful login (the Offers service). Must not fail the login: when
 * offers cannot be had, it returns a value that says so.
 */
@FunctionalInterface
public interface LoginOffers {

    /** @return a JSON-serialisable view of the user's offers */
    Object offersFor(String userId);
}
