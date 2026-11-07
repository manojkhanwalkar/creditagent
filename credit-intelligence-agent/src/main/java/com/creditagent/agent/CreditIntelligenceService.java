package com.creditagent.agent;

import com.creditagent.agent.advice.CreditAdvisor;
import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ScoreService;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import java.util.Objects;

/** Agent flow B–F in the system diagram: report over MCP, score, then recommendations. */
public class CreditIntelligenceService {

    private final CreditReportSource reports;
    private final ScoreService scores;
    private final CreditAdvisor advisor;

    public CreditIntelligenceService(CreditReportSource reports, ScoreService scores, CreditAdvisor advisor) {
        this.reports = Objects.requireNonNull(reports);
        this.scores = Objects.requireNonNull(scores);
        this.advisor = Objects.requireNonNull(advisor);
    }

    public CreditAdvice advise(Ssn ssn) {
        CreditReport report = reports.findReport(ssn).orElseThrow(ReportNotFoundException::new);
        CreditScore score = scores.score(report);
        return advisor.advise(report, score);
    }

    public static class ReportNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReportNotFoundException() {
            super("No credit report on file");
        }
    }
}
