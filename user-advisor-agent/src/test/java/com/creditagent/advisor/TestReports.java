package com.creditagent.advisor;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import java.time.Instant;
import java.util.List;

/** Synthetic reports for tests. */
public final class TestReports {

    private TestReports() {}

    /** Two cards at 60% and 20%, an auto loan, 3 late payments, 3 inquiries, 1 collection. */
    public static CreditReport stretched() {
        return new CreditReport("r-1", "***-**-6789", "Avery Doe", Instant.parse("2026-11-01T00:00:00Z"), List.of(
                new CreditAccount(AccountType.CREDIT_CARD, "Northwind Bank", AccountStatus.OPEN, 3000, 5000, 72, 1, 1, 0),
                new CreditAccount(AccountType.CREDIT_CARD, "Contoso Card", AccountStatus.OPEN, 1000, 5000, 18, 1, 0, 0),
                new CreditAccount(AccountType.AUTO_LOAN, "Fabrikam Auto", AccountStatus.CLOSED, 0, 20000, 48, 0, 0, 0)),
                3, 1, 0);
    }

    /** One card at 5%, nothing negative. */
    public static CreditReport clean() {
        return new CreditReport("r-2", "***-**-1111", "Blake Roe", Instant.parse("2026-11-01T00:00:00Z"), List.of(
                new CreditAccount(AccountType.CREDIT_CARD, "Northwind Bank", AccountStatus.OPEN, 250, 5000, 120, 0, 0, 0)),
                0, 0, 0);
    }
}
