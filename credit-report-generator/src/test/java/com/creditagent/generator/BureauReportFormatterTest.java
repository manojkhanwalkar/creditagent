package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.BureauReports;
import com.creditagent.common.bureau.EquifaxReport;
import com.creditagent.common.bureau.ExperianReport;
import com.creditagent.common.bureau.TransUnionReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class BureauReportFormatterTest {

    static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    static CreditReport report() {
        return new CreditReport(
                "r-1", "***-**-6789", "Mary Ann Patel", NOW,
                List.of(
                        new CreditAccount(AccountType.CREDIT_CARD, "Northwind Bank", AccountStatus.OPEN, 1_200, 5_000, 30, 1, 0, 0),
                        new CreditAccount(AccountType.AUTO_LOAN, "Proseware Auto", AccountStatus.CHARGED_OFF, 9_000, 20_000, 40, 0, 2, 1),
                        new CreditAccount(AccountType.MORTGAGE, "Adatum Mortgage", AccountStatus.CLOSED, 0, 300_000, 120, 0, 0, 0)),
                4, 1, 0);
    }

    @Test
    void eachBureauUsesItsOwnFormat() {
        BureauReports reports = new BureauReportFormatter(new Random(3)).format(report());

        EquifaxReport efx = reports.equifax();
        assertThat(efx.dateOfReport()).isEqualTo("11/07/2026");
        assertThat(efx.subject()).isEqualTo(new EquifaxReport.Subject("Mary Ann", "Patel", "6789"));
        assertThat(efx.trades()).allSatisfy(t -> {
            assertThat(t.dateOpened()).matches("\\d{2}/\\d{4}");
            assertThat(t.rate()).matches("[RIM][19]");
        });

        ExperianReport exp = reports.experian();
        assertThat(exp.reportDate()).isEqualTo("20261107");
        assertThat(exp.consumer()).isEqualTo(new ExperianReport.Consumer("PATEL, MARY ANN", "XXXXX6789"));
        assertThat(exp.tradelines()).allSatisfy(t -> {
            assertThat(t.subscriberName()).isUpperCase();
            assertThat(t.statusCode()).isIn("11", "13", "97");
            assertThat(t.paymentProfile()).matches("[0-3]+");
        });

        TransUnionReport tu = reports.transunion();
        assertThat(tu.reportDate()).isEqualTo("2026-11-07");
        assertThat(tu.ssnMasked()).isEqualTo("***-**-6789");
        assertThat(tu.trades()).allSatisfy(t -> assertThat(t.industryCode()).isIn("BC", "FA", "FM", "ES", "FP"));

        assertThat(efx.collectionItems()).isEqualTo(1);
        assertThat(exp.summary().collectionCount()).isEqualTo(1);
        assertThat(tu.collectionsCount()).isEqualTo(1);
    }

    @RepeatedTest(30)
    void everyAccountIsAtSomeBureauAndEveryInquiryAtExactlyOne() {
        CreditReport report = new CreditReportFactory(new Random(), Clock.fixed(NOW, ZoneOffset.UTC))
                .create(Ssn.parse("123-45-6789"), null);
        BureauReports reports = new BureauReportFormatter(new Random()).format(report);

        List<String> numbers = new java.util.ArrayList<>();
        reports.equifax().trades().forEach(t -> numbers.add(t.memberName() + t.accountNumber().substring(12)));
        reports.experian().tradelines().forEach(t -> numbers.add(t.subscriberName() + t.accountNumber().substring(8)));
        reports.transunion().trades().forEach(t -> numbers.add(t.subscriberName() + t.accountNumber().substring(0, 4)));
        assertThat(numbers.stream().map(String::toUpperCase).distinct().count()).isLessThanOrEqualTo(report.accounts().size());
        assertThat(numbers.size()).isGreaterThanOrEqualTo(report.accounts().size());

        int inquiries = reports.equifax().inquiries().size()
                + reports.experian().inquiries().size()
                + reports.transunion().inquiries().size();
        assertThat(inquiries).isEqualTo(report.hardInquiriesLast12Months());
    }

    @Test
    void latePaymentsSurviveInEveryFormat() {
        BureauReports reports = new BureauReportFormatter(new Random(11)).format(report());

        reports.equifax().trades().stream().filter(t -> t.memberName().equals("Proseware Auto")).forEach(t -> {
            assertThat(t.times60DaysLate()).isEqualTo(2);
            assertThat(t.times90DaysLate()).isEqualTo(1);
            assertThat(t.rate()).isEqualTo("I9");
        });
        reports.experian().tradelines().stream().filter(t -> t.subscriberName().equals("PROSEWARE AUTO")).forEach(t -> {
            assertThat(t.paymentProfile().chars().filter(c -> c == '2').count()).isEqualTo(2);
            assertThat(t.paymentProfile().chars().filter(c -> c == '3').count()).isEqualTo(1);
            assertThat(t.statusCode()).isEqualTo("97");
        });
        reports.transunion().trades().stream().filter(t -> t.subscriberName().equals("Adatum Mortgage")).forEach(t -> {
            assertThat(t.remark()).isEqualTo("CLOSED");
            assertThat(t.creditLimit()).isNull();
            assertThat(t.highCredit()).isEqualTo(300_000);
        });
    }

    @Test
    void splitsNames() {
        assertThat(BureauReportFormatter.splitName("Avery Patel")).containsExactly("Avery", "Patel");
        assertThat(BureauReportFormatter.splitName("Cher")).containsExactly("Cher", "");
    }
}
