package com.creditagent.nrg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.bureau.EquifaxReport;
import com.creditagent.common.bureau.ExperianReport;
import com.creditagent.common.bureau.TransUnionReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each bureau's codes, dates and amounts: what is read, and what is refused rather than guessed. */
class BureauReportParserTest {

    final BureauReportParser parser = new BureauReportParser();

    static EquifaxReport equifax(String rate, boolean closed, String opened, String reportDate, String last4) {
        return new EquifaxReport("EFX", reportDate, new EquifaxReport.Subject(null, null, last4),
                List.of(new EquifaxReport.Trade("Northwind Bank", "1234", "R", "18", opened, "11/05/2026",
                        1_500, 5_000, 100, rate, 0, 0, 0, closed)),
                List.of(), 0, 0);
    }

    static ExperianReport experian(String status, String balance, String profile, String name) {
        return new ExperianReport("EXP", "20261107", new ExperianReport.Consumer(name, "XXXXX6789"),
                List.of(new ExperianReport.Tradeline("NORTHWIND BANK", "1234", "18", "20231107", "20261020",
                        balance, null, status, profile)),
                List.of(), null);
    }

    static TransUnionReport transUnion(String rating, String remark, Long limit, Long high,
            TransUnionReport.HistoricalCounters lates) {
        return new TransUnionReport("TU", "2026-11-07", new TransUnionReport.Name("Avery", null), "***-**-6789",
                List.of(new TransUnionReport.Trade("Northwind Bank", "1234", "FA", "installment", "2023-11-07",
                        "2026-10-01", 900, limit, high, rating, lates, remark)),
                List.of(), 0, 0);
    }

    @ParameterizedTest
    @CsvSource({"R1,false,OPEN", "I5,false,OPEN", "M7,false,OPEN", "R1,true,CLOSED", "I8,false,CHARGED_OFF", "r9,false,CHARGED_OFF"})
    void equifaxRatesMapToStatuses(String rate, boolean closed, AccountStatus status) {
        assertThat(parser.parse(equifax(rate, closed, "11/2023", "11/07/2026", "6789")).accounts().getFirst().account().status())
                .isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({"R6", "R0", "X1", "''"})
    void unknownEquifaxRatesAreRefused(String rate) {
        assertThatThrownBy(() -> parser.parse(equifax(rate, false, "11/2023", "11/07/2026", "6789")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("rate");
        assertThatThrownBy(() -> parser.parse(equifax(null, false, "11/2023", "11/07/2026", "6789")))
                .isInstanceOf(InvalidBureauReportException.class);
    }

    @Test
    void equifaxDatesAndIdentityAreValidated() {
        assertThatThrownBy(() -> parser.parse(equifax("R1", false, "2023-11", "11/07/2026", "6789")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("MM/yyyy");
        assertThatThrownBy(() -> parser.parse(equifax("R1", false, null, "11/07/2026", "6789")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> parser.parse(equifax("R1", false, "11/2023", "2026-11-07", "6789")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("valid date");
        assertThatThrownBy(() -> parser.parse(equifax("R1", false, "11/2023", "11/07/2026", "")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("last four");
        // No first or last name: an empty name, not "null null".
        assertThat(parser.parse(equifax("R1", false, "11/2023", "11/07/2026", "6789")).fullName()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"11,OPEN", "84,OPEN", "13,CLOSED", "93,CHARGED_OFF", "97,CHARGED_OFF"})
    void experianStatusesMapToStatuses(String code, AccountStatus status) {
        assertThat(parser.parse(experian(code, "100", "", "PATEL, AVERY")).accounts().getFirst().account().status())
                .isEqualTo(status);
    }

    @Test
    void experianValuesAreReadOrRefused() {
        assertThatThrownBy(() -> parser.parse(experian("42", "100", "", "PATEL, AVERY")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("statusCode");
        assertThatThrownBy(() -> parser.parse(experian(null, "100", "", "PATEL, AVERY")))
                .isInstanceOf(InvalidBureauReportException.class);
        assertThatThrownBy(() -> parser.parse(experian("11", "12.50", "", "PATEL, AVERY")))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("whole-dollar");

        BureauView view = parser.parse(experian("11", "$1,250", null, "AVERY PATEL"));
        assertThat(view.accounts().getFirst().account().balance()).isEqualTo(1_250);
        assertThat(view.accounts().getFirst().account().creditLimit()).isZero(); // blank amount
        assertThat(view.accounts().getFirst().account().latePayments30()).isZero(); // no payment profile
        assertThat(view.fullName()).isEqualTo("Avery Patel"); // no comma: already first-last
        assertThat(parser.parse(experian("11", "1", "", null)).fullName()).isEmpty();
        assertThat(view.collections()).isZero(); // no summary
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"01||OPEN", "05||OPEN", "07|closed|CLOSED", "08||CHARGED_OFF", "09||CHARGED_OFF"})
    void transUnionRatingsMapToStatuses(String rating, String remark, AccountStatus status) {
        assertThat(parser.parse(transUnion(rating, remark, 5_000L, null, null)).accounts().getFirst().account().status())
                .isEqualTo(status);
    }

    @Test
    void transUnionLimitsAndCountersHaveSensibleDefaults() {
        assertThatThrownBy(() -> parser.parse(transUnion("06", null, null, null, null)))
                .isInstanceOf(InvalidBureauReportException.class).hasMessageContaining("accountRating");
        assertThatThrownBy(() -> parser.parse(transUnion(null, null, null, null, null)))
                .isInstanceOf(InvalidBureauReportException.class);

        // Instalment trades have no credit limit: the original amount is used, else zero.
        assertThat(parser.parse(transUnion("01", null, null, 20_000L, null)).accounts().getFirst().account().creditLimit())
                .isEqualTo(20_000);
        assertThat(parser.parse(transUnion("01", null, null, null, null)).accounts().getFirst().account().creditLimit())
                .isZero();
        assertThat(parser.parse(transUnion("01", null, 5_000L, null, null)).accounts().getFirst().account().latePayments90())
                .isZero();
        assertThat(parser.parse(transUnion("01", null, 5_000L, null, null)).fullName()).isEqualTo("Avery");
    }

    @Test
    void creditorNamesAreMatchedIgnoringCaseAndPunctuation() {
        assertThat(new AccountKey(com.creditagent.common.AccountType.CREDIT_CARD, "Northwind Bank, N.A.", "1234"))
                .isEqualTo(new AccountKey(com.creditagent.common.AccountType.CREDIT_CARD, "NORTHWIND BANK NA", "1234"));
        assertThat(new AccountKey(com.creditagent.common.AccountType.CREDIT_CARD, null, "1234").creditor()).isEmpty();
    }
}
