package com.creditagent.common.bureau;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BureauReportsTest {

    static EquifaxReport equifax(String number) {
        return new EquifaxReport(number, "11/07/2026", new EquifaxReport.Subject("Avery", "Patel", "6789"),
                List.of(new EquifaxReport.Trade("Northwind Bank", "XXXXXXXX1234", "R", "18", "11/2023", "10/20/2026",
                        5000, 5000, 250, "R1", 0, 0, 0, false)),
                List.of(new EquifaxReport.Inquiry("Contoso Auto", "07/01/2026")), 0, 0);
    }

    static ExperianReport experian(String id) {
        return new ExperianReport(id, "20261107", new ExperianReport.Consumer("PATEL, AVERY", "XXXXX6789"),
                List.of(new ExperianReport.Tradeline("NORTHWIND BANK", "XXXXXXXX1234", "18", "20231107", "20261020",
                        "250", "5000", "11", "000000")),
                List.of(new ExperianReport.Inquiry("CONTOSO AUTO", "20260701")), new ExperianReport.Summary(0, 0));
    }

    static TransUnionReport transUnion(String file) {
        return new TransUnionReport(file, "2026-11-07", new TransUnionReport.Name("Avery", "Patel"), "XXX-XX-6789",
                List.of(new TransUnionReport.Trade("Northwind Bank", "XXXXXXXX1234", "BC", "revolving", "2023-11-07",
                        "2026-10-20", 250, 5000L, null, "01", new TransUnionReport.HistoricalCounters(0, 0, 0), null)),
                List.of(new TransUnionReport.Inquiry("Contoso Auto", "2026-07-01")), 0, 0);
    }

    @Test
    void missingListsBecomeEmptyAndListsAreCopied() {
        List<EquifaxReport.Trade> trades = new ArrayList<>(equifax("E").trades());
        EquifaxReport eq = new EquifaxReport("E", "11/07/2026", null, trades, null, 0, 0);
        trades.clear();
        assertThat(eq.trades()).hasSize(1);
        assertThat(eq.inquiries()).isEmpty();
        assertThatThrownBy(() -> eq.trades().add(null)).isInstanceOf(UnsupportedOperationException.class);

        assertThat(new ExperianReport("X", "20261107", null, null, null, null).tradelines()).isEmpty();
        assertThat(new ExperianReport("X", "20261107", null, null, null, null).inquiries()).isEmpty();
        assertThat(new TransUnionReport("T", "2026-11-07", null, null, null, null, 0, 0).trades()).isEmpty();
        assertThat(new TransUnionReport("T", "2026-11-07", null, null, null, null, 0, 0).inquiries()).isEmpty();
    }

    @Test
    void theSetIsEmptyOnlyWhenNoBureauHasAReport() {
        assertThat(new BureauReports(null, null, null).isEmpty()).isTrue();
        assertThat(new BureauReports(equifax("E"), null, null).isEmpty()).isFalse();
        assertThat(new BureauReports(null, experian("X"), null).isEmpty()).isFalse();
        assertThat(new BureauReports(null, null, transUnion("T")).isEmpty()).isFalse();
    }

    @Test
    void theNormalizedReportIdIsStableAndDependsOnEveryBureausReportId() {
        BureauReports all = new BureauReports(equifax("E-1"), experian("X-1"), transUnion("T-1"));
        assertThat(all.normalizedReportId())
                .isEqualTo(new BureauReports(equifax("E-1"), experian("X-1"), transUnion("T-1")).normalizedReportId())
                .matches("[0-9a-f-]{36}");
        assertThat(all.normalizedReportId())
                .isNotEqualTo(new BureauReports(equifax("E-2"), experian("X-1"), transUnion("T-1")).normalizedReportId())
                .isNotEqualTo(new BureauReports(null, experian("X-1"), transUnion("T-1")).normalizedReportId());
        assertThat(new BureauReports(null, null, null).normalizedReportId()).matches("[0-9a-f-]{36}");
    }
}
