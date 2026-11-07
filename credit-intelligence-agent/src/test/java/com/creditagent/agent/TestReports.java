package com.creditagent.agent;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import java.time.Instant;
import java.util.List;

public final class TestReports {

    private TestReports() {}

    public static CreditAccount card(String creditor, long balance, long limit, int monthsOpen, int late30) {
        return new CreditAccount(AccountType.CREDIT_CARD, creditor, AccountStatus.OPEN, balance, limit, monthsOpen, late30, 0, 0);
    }

    public static CreditReport report(List<CreditAccount> accounts, int inquiries, int collections, int publicRecords) {
        return new CreditReport(
                "r-test", "***-**-6789", "Avery Patel", Instant.parse("2026-11-01T00:00:00Z"),
                accounts, inquiries, collections, publicRecords);
    }

    /** Clean file: low utilization, long history, mixed types, nothing negative. */
    public static CreditReport clean() {
        return report(
                List.of(
                        card("Northwind Bank", 300, 10_000, 120, 0),
                        new CreditAccount(AccountType.MORTGAGE, "Adatum Mortgage", AccountStatus.OPEN, 200_000, 300_000, 60, 0, 0, 0)),
                0, 0, 0);
    }

    /** Troubled file: maxed cards, late payments, collections, many inquiries, short history. */
    public static CreditReport troubled() {
        return report(
                List.of(card("Contoso Card", 1_950, 2_000, 14, 2), card("Fabrikam Credit", 2_800, 3_000, 20, 0)),
                5, 1, 0);
    }
}
