package com.creditagent.nrg;

import com.creditagent.common.AccountType;
import java.util.Locale;

/**
 * Identifies the same tradeline across bureaus: account type, creditor (case and punctuation
 * ignored, since Experian upper-cases names) and the last four digits of the account number.
 */
record AccountKey(AccountType type, String creditor, String accountLastFour) {

    AccountKey {
        creditor = normalise(creditor);
    }

    static String normalise(String name) {
        return name == null ? "" : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
