package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class CreditReportFactoryTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    static final Ssn SSN = Ssn.parse("123-45-6789");

    @Test
    void storesOnlyTheMaskedSsn() {
        CreditReport report = new CreditReportFactory(new Random(1), CLOCK).create(SSN, "Avery Patel");
        assertThat(report.ssnMasked()).isEqualTo("***-**-6789");
        assertThat(report.toString()).doesNotContain("123456789").doesNotContain("123-45-6789");
        assertThat(report.fullName()).isEqualTo("Avery Patel");
        assertThat(report.generatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void picksANameWhenNoneIsGiven() {
        assertThat(new CreditReportFactory(new Random(1), CLOCK).create(SSN, " ").fullName()).isNotBlank();
    }

    @RepeatedTest(50)
    void generatedReportsArePlausible() {
        CreditReport report = new CreditReportFactory(new Random(), CLOCK).create(SSN, null);
        assertThat(report.accounts()).isNotEmpty();
        assertThat(report.accounts()).anyMatch(a -> a.type() == AccountType.CREDIT_CARD);
        for (CreditAccount account : report.accounts()) {
            assertThat(account.balance()).isNotNegative();
            assertThat(account.creditLimit()).isPositive();
            assertThat(account.monthsOpen()).isPositive();
            assertThat(account.totalLatePayments()).isNotNegative();
        }
        assertThat(report.hardInquiriesLast12Months()).isBetween(0, 6);
        assertThat(report.collections()).isNotNegative();
    }
}
