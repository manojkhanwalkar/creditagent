package com.creditagent.web.auth;

import com.creditagent.common.Ssn;

/**
 * Told once a new account exists, with the SSN the KYC check read from the user's card: the only time
 * the server holds a user's SSN without them typing it. Used to record a first credit score, so offers
 * can be matched at the first login. Must not keep the SSN, and must not block: registration waits
 * for it. A listener that fails does not fail the registration.
 */
@FunctionalInterface
public interface RegistrationListener {

    void registered(String userId, Ssn ssn);
}
