package com.creditagent.agent;

import com.creditagent.agent.advice.CreditAdvisor;
import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ScoreService;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import java.util.Objects;

/**
 * Agent flow B–F in the system diagram: report over MCP, score, then recommendations.
 *
 * <p>The agent is a third party to the consumer, so it first checks that the consumer has not locked
 * their credit report, and refuses if they have. If the lock cannot be checked, it refuses too
 * ({@link com.creditagent.clients.ServiceUnavailableException}): a lock must not fail open.
 */
public class CreditIntelligenceService {

    private final CreditReportSource reports;
    private final ScoreService scores;
    private final CreditAdvisor advisor;
    private final ReportLockCheck locks;

    public CreditIntelligenceService(
            CreditReportSource reports, ScoreService scores, CreditAdvisor advisor, ReportLockCheck locks) {
        this.locks = Objects.requireNonNull(locks);
        this.reports = Objects.requireNonNull(reports);
        this.scores = Objects.requireNonNull(scores);
        this.advisor = Objects.requireNonNull(advisor);
    }

    public CreditAdvice advise(Ssn ssn) {
        if (locks.isLocked(ssn)) {
            throw new ReportLockedException();
        }
        CreditReport report = reports.findReport(ssn).orElseThrow(ReportNotFoundException::new);
        CreditScore score = scores.score(report);
        return advisor.advise(report, score);
    }

    /** The consumer has locked their credit report against third-party pulls. */
    public static class ReportLockedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public static final String MESSAGE = "This credit report is locked by its owner.";

        ReportLockedException() {
            super(MESSAGE);
        }
    }

    public static class ReportNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReportNotFoundException() {
            super("No credit report on file");
        }
    }
}
