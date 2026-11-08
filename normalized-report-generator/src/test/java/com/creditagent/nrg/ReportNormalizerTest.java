package com.creditagent.nrg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.bureau.BureauReports;
import com.creditagent.common.bureau.EquifaxReport;
import com.creditagent.common.bureau.ExperianReport;
import com.creditagent.common.bureau.TransUnionReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportNormalizerTest {

    static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    final ReportNormalizer normalizer = new ReportNormalizer(new BureauReportParser(), Clock.fixed(NOW, ZoneOffset.UTC));

    /** Northwind card at all three bureaus; Equifax's update is newest. */
    static EquifaxReport equifax() {
        return new EquifaxReport(
                "EFX-1", "11/07/2026",
                new EquifaxReport.Subject("Avery", "Patel", "6789"),
                List.of(
                        new EquifaxReport.Trade("Northwind Bank", "************1234", "R", "18", "11/2023", "11/05/2026",
                                1_500, 5_000, 1_200, "R1", 1, 0, 0, false),
                        new EquifaxReport.Trade("Adatum Mortgage", "************5555", "M", "26", "11/2016", "10/20/2026",
                                300_000, 0, 250_000, "M1", 0, 0, 0, false)),
                List.of(new EquifaxReport.Inquiry("Litware Lending", "03/02/2026")),
                0, 0);
    }

    static ExperianReport experian() {
        return new ExperianReport(
                "EXP-1", "20261107",
                new ExperianReport.Consumer("PATEL, AVERY", "XXXXX6789"),
                List.of(
                        new ExperianReport.Tradeline("NORTHWIND BANK", "XXXXXXXX1234", "18", "20231107", "20261020",
                                "1000", "5000", "11", "0010000000000000000000000000000000000"),
                        new ExperianReport.Tradeline("PROSEWARE AUTO", "XXXXXXXX9876", "00", "20230707", "20261101",
                                "9000", "20000", "97", "0023000000")),
                List.of(
                        new ExperianReport.Inquiry("LITWARE LENDING", "20260302"),
                        new ExperianReport.Inquiry("CONTOSO CARD", "20250101")),
                new ExperianReport.Summary(2, 0));
    }

    static TransUnionReport transUnion() {
        return new TransUnionReport(
                "TU-1", "2026-11-07",
                new TransUnionReport.Name("Avery", "Patel"),
                "***-**-6789",
                List.of(new TransUnionReport.Trade("Northwind Bank", "1234****", "BC", "revolving", "2023-11-07",
                        "2026-10-01", 900, 5_000L, 1_500L, "01",
                        new TransUnionReport.HistoricalCounters(1, 0, 0), null)),
                List.of(new TransUnionReport.Inquiry("Fabrikam Credit", "2026-08-15")),
                1, 1);
    }

    @Test
    void mergesTheThreeBureausIntoOneReport() {
        CreditReport report = normalizer.normalize(new BureauReports(equifax(), experian(), transUnion()));

        assertThat(report.ssnMasked()).isEqualTo("***-**-6789");
        assertThat(report.fullName()).isEqualTo("Avery Patel");
        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(report.accounts()).containsExactly(
                // Same card at three bureaus: newest balance (Equifax), lates not double-counted.
                new CreditAccount(AccountType.CREDIT_CARD, "Northwind Bank", AccountStatus.OPEN, 1_200, 5_000, 36, 1, 0, 0),
                new CreditAccount(AccountType.MORTGAGE, "Adatum Mortgage", AccountStatus.OPEN, 250_000, 300_000, 120, 0, 0, 0),
                new CreditAccount(AccountType.AUTO_LOAN, "PROSEWARE AUTO", AccountStatus.CHARGED_OFF, 9_000, 20_000, 40, 0, 1, 1));
        // Litware at Equifax and Experian is one pull; Contoso is older than 12 months.
        assertThat(report.hardInquiriesLast12Months()).isEqualTo(2);
        assertThat(report.collections()).isEqualTo(2);
        assertThat(report.publicRecords()).isEqualTo(1);
    }

    @Test
    void worksWithASingleBureau() {
        CreditReport report = normalizer.normalize(new BureauReports(null, experian(), null));
        assertThat(report.fullName()).isEqualTo("Avery Patel");
        assertThat(report.accounts()).hasSize(2);
        assertThat(report.accounts().getFirst().latePayments30()).isEqualTo(1);
    }

    @Test
    void chargedOffAnywhereWins() {
        TransUnionReport tu = transUnion();
        TransUnionReport.Trade card = tu.trades().getFirst();
        TransUnionReport chargedOff = new TransUnionReport(tu.fileNumber(), tu.reportDate(), tu.name(), tu.ssnMasked(),
                List.of(new TransUnionReport.Trade(card.subscriberName(), card.accountNumber(), card.industryCode(),
                        card.portfolioType(), card.dateOpened(), card.dateEffective(), card.currentBalance(),
                        card.creditLimit(), card.highCredit(), "09", card.historicalCounters(), null)),
                tu.inquiries(), 0, 0);

        CreditReport report = normalizer.normalize(new BureauReports(equifax(), null, chargedOff));

        assertThat(report.accounts().getFirst().status()).isEqualTo(AccountStatus.CHARGED_OFF);
    }

    @Test
    void refusesReportsForDifferentConsumers() {
        TransUnionReport tu = transUnion();
        TransUnionReport other = new TransUnionReport(tu.fileNumber(), tu.reportDate(), tu.name(), "***-**-1111",
                tu.trades(), tu.inquiries(), 0, 0);
        assertThatThrownBy(() -> normalizer.normalize(new BureauReports(equifax(), null, other)))
                .isInstanceOf(InvalidBureauReportException.class)
                .hasMessage("Bureau reports are for different consumers");
    }

    @Test
    void refusesAnEmptyRequest() {
        assertThatThrownBy(() -> normalizer.normalize(new BureauReports(null, null, null)))
                .isInstanceOf(InvalidBureauReportException.class);
    }

    @Test
    void badFieldsAreNamedButNotEchoed() {
        EquifaxReport bad = new EquifaxReport("EFX-1", "2026-13-45", equifax().subject(), List.of(), List.of(), 0, 0);
        assertThatThrownBy(() -> normalizer.normalize(new BureauReports(bad, null, null)))
                .isInstanceOf(InvalidBureauReportException.class)
                .hasMessageContaining("dateOfReport")
                .hasMessageNotContaining("2026-13-45");

        ExperianReport exp = experian();
        ExperianReport badCode = new ExperianReport(exp.reportId(), exp.reportDate(), exp.consumer(),
                List.of(new ExperianReport.Tradeline("X", "1234", "99", "20200101", "20261101", "0", "0", "11", "")),
                List.of(), null);
        assertThatThrownBy(() -> normalizer.normalize(new BureauReports(null, badCode, null)))
                .isInstanceOf(InvalidBureauReportException.class)
                .hasMessageContaining("account type");
    }
}
