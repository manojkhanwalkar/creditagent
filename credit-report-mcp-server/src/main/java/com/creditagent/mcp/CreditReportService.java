package com.creditagent.mcp;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Reads all of a consumer's bureau reports and has NRG merge them into one. */
@Service
public class CreditReportService {

    private final CreditFileRepository repository;
    private final ReportNormalizerClient normalizer;

    public CreditReportService(CreditFileRepository repository, ReportNormalizerClient normalizer) {
        this.repository = repository;
        this.normalizer = normalizer;
    }

    /** The normalized report as JSON, or empty if no bureau has a file for this SSN. */
    public Optional<String> findNormalizedReport(Ssn ssn) {
        Map<Bureau, String> reports = repository.findBureauReports(ssn);
        return reports.isEmpty() ? Optional.empty() : Optional.of(normalizer.normalize(reports));
    }
}
