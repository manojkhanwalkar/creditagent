package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditReportTest {

    static CreditAccount card(long balance, long limit, AccountStatus status) {
        return new CreditAccount(AccountType.CREDIT_CARD, "Bank", status, balance, limit, 24, 0, 0, 0);
    }

    static CreditReport report(List<CreditAccount> accounts) {
        return new CreditReport("r1", "***-**-6789", "Test Person", Instant.EPOCH, accounts, 0, 0, 0);
    }

    @Test
    void utilizationCountsOnlyOpenCreditCards() {
        CreditReport report = report(List.of(
                card(500, 1_000, AccountStatus.OPEN),
                card(1_500, 1_000, AccountStatus.CLOSED),
                new CreditAccount(AccountType.AUTO_LOAN, "Lender", AccountStatus.OPEN, 9_000, 10_000, 12, 0, 0, 0)));
        assertThat(report.revolvingUtilization()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void utilizationIsZeroWithoutRevolvingCredit() {
        assertThat(report(List.of()).revolvingUtilization()).isZero();
    }

    @Test
    void aggregatesLatePaymentsHistoryAndMix() {
        CreditReport report = report(List.of(
                new CreditAccount(AccountType.CREDIT_CARD, "A", AccountStatus.OPEN, 0, 1_000, 10, 1, 0, 0),
                new CreditAccount(AccountType.MORTGAGE, "B", AccountStatus.OPEN, 0, 1_000, 90, 0, 2, 1)));
        assertThat(report.totalLatePayments()).isEqualTo(4);
        assertThat(report.oldestAccountMonths()).isEqualTo(90);
        assertThat(report.distinctAccountTypes()).isEqualTo(2);
    }

    @Test
    void accountsListIsDefensivelyCopied() {
        assertThat(report(null).accounts()).isEmpty();
    }
}
