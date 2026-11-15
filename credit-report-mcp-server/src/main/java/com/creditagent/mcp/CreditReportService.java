package com.creditagent.mcp;

import com.creditagent.common.AuditLog;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reads all of a consumer's bureau reports and has NRG merge them into one. Every read, found or not,
 * is an audit record (who asked, a keyed reference to whose report, the outcome): every path to a
 * report - the web server, the agents, the Claude connector, any MCP host - comes through here.
 */
@Service
public class CreditReportService {

    /** How the report was asked for, recorded as the audit actor. */
    public enum Via {
        /** {@code resources/read} with the internal service token (web server, agents, connector). */
        RESOURCE_SERVICE("service:mcp-resource"),
        /** {@code resources/read} by an MCP host without the token (lock checked). */
        RESOURCE_HOST("mcp-host:resource"),
        /** The {@code get_credit_report} tool (lock checked). */
        TOOL("mcp-host:tool");

        final String actor;

        Via(String actor) {
            this.actor = actor;
        }
    }

    private final CreditReportStore store;
    private final ReportNormalizerClient normalizer;
    private final AuditLog audit;

    @Autowired
    public CreditReportService(CreditReportStore store, ReportNormalizerClient normalizer, AuditLog audit) {
        this.store = store;
        this.normalizer = normalizer;
        this.audit = audit;
    }

    /** With the default audit logger. */
    public CreditReportService(CreditReportStore store, ReportNormalizerClient normalizer) {
        this(store, normalizer, new AuditLog());
    }

    /** The normalized report as JSON, or empty if no bureau has a report for this SSN. */
    public Optional<String> findNormalizedReport(Ssn ssn, Via via) {
        String subject = "ssnref:" + store.reference(ssn);
        try {
            Map<Bureau, String> reports = store.findAll(ssn);
            Optional<String> report = reports.isEmpty() ? Optional.empty() : Optional.of(normalizer.normalize(reports));
            audit.record(AuditLog.Event.REPORT_READ, via.actor, subject, report.isPresent() ? "FOUND" : "NOT_FOUND");
            return report;
        } catch (RuntimeException e) {
            audit.record(AuditLog.Event.REPORT_READ, via.actor, subject, "FAILED");
            throw e;
        }
    }
}
