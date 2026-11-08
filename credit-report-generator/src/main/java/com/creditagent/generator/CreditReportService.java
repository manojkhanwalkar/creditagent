package com.creditagent.generator;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.common.bureau.BureauReports;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CreditReportService {

    /**
     * What was generated.
     *
     * @param reportId the id the normalized report for these files will carry
     * @param report the consumer's synthetic history
     * @param bureaus the bureaus that now hold a file
     */
    public record GeneratedReports(String reportId, CreditReport report, List<Bureau> bureaus) {}

    private final CreditReportFactory factory;
    private final BureauReportFormatter formatter;
    private final CreditFileWriter writer;

    public CreditReportService(CreditReportFactory factory, BureauReportFormatter formatter, CreditFileWriter writer) {
        this.factory = factory;
        this.formatter = formatter;
        this.writer = writer;
    }

    /** Creates a synthetic credit history and writes one report per bureau, each in its own format. */
    public GeneratedReports generate(Ssn ssn, String fullName) {
        CreditReport report = factory.create(ssn, fullName);
        BureauReports bureauReports = formatter.format(report);
        Map<Bureau, Object> files = new EnumMap<>(Bureau.class);
        files.put(Bureau.EQUIFAX, bureauReports.equifax());
        files.put(Bureau.EXPERIAN, bureauReports.experian());
        files.put(Bureau.TRANSUNION, bureauReports.transunion());
        List<Bureau> written = List.copyOf(writer.writeAll(ssn, files).keySet());
        return new GeneratedReports(bureauReports.normalizedReportId(), report, written);
    }
}
