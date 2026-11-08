package com.creditagent.generator;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.common.bureau.BureauReports;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CreditReportService {

    /** What was generated: the consumer's history and the bureaus it was written to. */
    public record GeneratedReports(CreditReport report, List<Bureau> bureaus) {}

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
        List<Bureau> written = new ArrayList<>();
        write(ssn, Bureau.EQUIFAX, bureauReports.equifax(), written);
        write(ssn, Bureau.EXPERIAN, bureauReports.experian(), written);
        write(ssn, Bureau.TRANSUNION, bureauReports.transunion(), written);
        return new GeneratedReports(report, List.copyOf(written));
    }

    private void write(Ssn ssn, Bureau bureau, Object report, List<Bureau> written) {
        if (report != null) {
            writer.write(ssn, bureau, report);
            written.add(bureau);
        }
    }
}
