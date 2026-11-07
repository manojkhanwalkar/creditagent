package com.creditagent.web;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ScoreService;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import java.util.Objects;

/** Request flow 2–7 in the system diagram: fetch the report over MCP, then ask for a score. */
public class CreditScoreService {

    private final CreditReportSource reports;
    private final ScoreService scores;

    public CreditScoreService(CreditReportSource reports, ScoreService scores) {
        this.reports = Objects.requireNonNull(reports);
        this.scores = Objects.requireNonNull(scores);
    }

    public CreditScore scoreFor(Ssn ssn) {
        CreditReport report = reports.findReport(ssn).orElseThrow(CreditReportNotFoundException::new);
        return scores.score(report);
    }

    public static class CreditReportNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CreditReportNotFoundException() {
            super("No credit report on file");
        }
    }
}
