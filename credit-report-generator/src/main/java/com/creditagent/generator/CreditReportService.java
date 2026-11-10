package com.creditagent.generator;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.common.bureau.BureauReports;
import com.creditagent.reports.CreditReportStore;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CreditReportService {

    /**
     * What was generated.
     *
     * @param reportId the id the normalized report for these files will carry
     * @param report the consumer's synthetic history
     * @param bureaus the bureaus that now hold a report
     */
    public record GeneratedReports(String reportId, CreditReport report, List<Bureau> bureaus) {}

    private final CreditReportFactory factory;
    private final BureauReportFormatter formatter;
    private final CreditReportStore store;
    private final JsonMapper json;

    public CreditReportService(CreditReportFactory factory, BureauReportFormatter formatter, CreditReportStore store,
            JsonMapper json) {
        this.factory = factory;
        this.formatter = formatter;
        this.store = store;
        this.json = json;
    }

    /**
     * Creates a synthetic credit history and stores one report per bureau, each in its own format,
     * replacing this consumer's previous set.
     */
    public GeneratedReports generate(Ssn ssn, String fullName) {
        CreditReport report = factory.create(ssn, fullName);
        BureauReports bureauReports = formatter.format(report);
        Map<Bureau, String> reports = new EnumMap<>(Bureau.class);
        put(reports, Bureau.EQUIFAX, bureauReports.equifax());
        put(reports, Bureau.EXPERIAN, bureauReports.experian());
        put(reports, Bureau.TRANSUNION, bureauReports.transunion());
        store.replaceAll(ssn, reports);
        List<Bureau> written = List.copyOf(reports.keySet());
        return new GeneratedReports(bureauReports.normalizedReportId(), report, written);
    }

    /** A bureau with nothing reported for this consumer holds no report. */
    private void put(Map<Bureau, String> reports, Bureau bureau, Object report) {
        if (report != null) {
            reports.put(bureau, json.writeValueAsString(report));
        }
    }
}
