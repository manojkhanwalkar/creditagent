package com.creditagent.common;

import java.time.Instant;
import java.util.List;

/**
 * A credit report as returned by the Credit Report MCP server.
 *
 * <p>The report carries only the masked SSN. The bureau reports it is merged from are stored under a
 * keyed hash of the SSN ({@link SsnHmac}), encrypted, so neither the content nor the table exposes
 * the full number.
 */
public record CreditReport(
        String reportId,
        String ssnMasked,
        String fullName,
        Instant generatedAt,
        List<CreditAccount> accounts,
        int hardInquiriesLast12Months,
        int collections,
        int publicRecords) {

    public CreditReport {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public long totalRevolvingBalance() {
        return accounts.stream()
                .filter(a -> a.type() == AccountType.CREDIT_CARD && a.isOpen())
                .mapToLong(CreditAccount::balance)
                .sum();
    }

    public long totalRevolvingLimit() {
        return accounts.stream()
                .filter(a -> a.type() == AccountType.CREDIT_CARD && a.isOpen())
                .mapToLong(CreditAccount::creditLimit)
                .sum();
    }

    /** Revolving utilisation as a fraction (0.0 – n), or 0 when there is no revolving credit. */
    public double revolvingUtilization() {
        long limit = totalRevolvingLimit();
        return limit == 0 ? 0.0 : (double) totalRevolvingBalance() / limit;
    }

    public int totalLatePayments() {
        return accounts.stream().mapToInt(CreditAccount::totalLatePayments).sum();
    }

    public int oldestAccountMonths() {
        return accounts.stream().mapToInt(CreditAccount::monthsOpen).max().orElse(0);
    }

    public long distinctAccountTypes() {
        return accounts.stream().map(CreditAccount::type).distinct().count();
    }
}
