package com.creditagent.mcp;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Reads all of a consumer's bureau reports and has NRG merge them into one. */
@Service
public class CreditReportService {

    private final CreditReportStore store;
    private final ReportNormalizerClient normalizer;

    public CreditReportService(CreditReportStore store, ReportNormalizerClient normalizer) {
        this.store = store;
        this.normalizer = normalizer;
    }

    /** The normalized report as JSON, or empty if no bureau has a report for this SSN. */
    public Optional<String> findNormalizedReport(Ssn ssn) {
        Map<Bureau, String> reports = store.findAll(ssn);
        return reports.isEmpty() ? Optional.empty() : Optional.of(normalizer.normalize(reports));
    }
}
