package com.creditagent.generator;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import org.springframework.stereotype.Service;

@Service
public class CreditReportService {

    private final CreditReportFactory factory;
    private final CreditFileWriter writer;

    public CreditReportService(CreditReportFactory factory, CreditFileWriter writer) {
        this.factory = factory;
        this.writer = writer;
    }

    public CreditReport generate(Ssn ssn, String fullName) {
        CreditReport report = factory.create(ssn, fullName);
        writer.write(ssn, report);
        return report;
    }
}
