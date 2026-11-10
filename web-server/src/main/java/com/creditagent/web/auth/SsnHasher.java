package com.creditagent.web.auth;

import com.creditagent.common.Ssn;
import com.creditagent.common.SsnHmac;

/**
 * Keyed hash of an SSN, so the users table can tie an account to its SSN without storing the SSN.
 * See {@link SsnHmac} for why it must be keyed. Uses its own key ({@code SSN_HMAC_KEY}), separate
 * from the key of the CreditReports table's SSN hash (CREDIT_FILE_HMAC_KEY).
 */
public final class SsnHasher {

    private final SsnHmac hmac;

    public SsnHasher(String secret) {
        this.hmac = new SsnHmac(secret, "SSN HMAC key");
    }

    public String hash(Ssn ssn) {
        return hmac.hex(ssn);
    }

    /** Constant-time comparison of {@code ssn}'s hash with a stored one. */
    public boolean matches(Ssn ssn, String storedHash) {
        return hmac.matches(ssn, storedHash);
    }
}
