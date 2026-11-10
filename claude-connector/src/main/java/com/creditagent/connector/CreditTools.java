package com.creditagent.connector;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ScoreService;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.SafeLogging;
import com.creditagent.common.ScoreBand;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each connector tool does, always for the user the request acts for. No tool takes an SSN:
 * the user's own comes from their access token, so Claude can only ever reach that user's data.
 *
 * <p>Reads of the report and score are the owner's own, like the web UI's, so they are not refused
 * when the report is locked. Advice comes from the Credit Intelligence Agent, which is a third party
 * to the report and refuses while it is locked.
 */
public class CreditTools {

    private static final Logger log = LoggerFactory.getLogger(CreditTools.class);

    static final String UNAVAILABLE = "The credit service is temporarily unavailable. Please try again in a moment.";
    static final String NO_REPORT = "There is no credit report on file for this account.";
    static final String RECONNECT = "This CreditAgent connection has ended. Reconnect the connector to continue.";
    static final String LOCKED_ADVICE = "Your credit report is locked, so the Credit Intelligence Agent cannot read it."
            + " Unlock it (set_report_lock with locked=false) to get advice, and lock it again afterwards.";
    static final String ADVICE_REFUSED = "The Credit Intelligence Agent could not give advice for this report.";

    /** A tool failed in a way the user should be told about; the message is safe to show. */
    public static class ToolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message, null, false, false);
        }
    }

    public record Score(String ssnMasked, int score, int maxScore, ScoreBand band) {}

    public record LockStatus(String reportStatus, boolean locked) {
        static LockStatus of(String status) {
            return new LockStatus(status, "LOCKED".equals(status));
        }
    }

    private final CreditReportSource reports;
    private final ScoreService scores;
    private final AdviceClient advice;
    private final AccountClient accounts;

    public CreditTools(CreditReportSource reports, ScoreService scores, AdviceClient advice, AccountClient accounts) {
        this.reports = Objects.requireNonNull(reports);
        this.scores = Objects.requireNonNull(scores);
        this.advice = Objects.requireNonNull(advice);
        this.accounts = Objects.requireNonNull(accounts);
    }

    public CreditReport report(ConnectorUser user) {
        return guarded(() -> reports.findReport(user.ssn()).orElseThrow(() -> new ToolException(NO_REPORT)));
    }

    public Score score(ConnectorUser user) {
        CreditReport report = report(user);
        CreditScore score = guarded(() -> scores.score(report));
        return new Score(user.ssn().masked(), score.value(), score.max(), score.band());
    }

    public CreditAdvice advice(ConnectorUser user) {
        return guarded(() -> {
            try {
                return advice.adviceFor(user.ssn());
            } catch (AdviceClient.AdviceRefusedException e) {
                throw new ToolException(ReportLockCheck.LOCKED_MESSAGE.equals(e.getMessage()) ? LOCKED_ADVICE : ADVICE_REFUSED);
            }
        });
    }

    public LockStatus lockStatus(ConnectorUser user) {
        return LockStatus.of(guarded(() -> accounts.reportStatus(user)));
    }

    public LockStatus setLock(ConnectorUser user, boolean locked) {
        LockStatus status = LockStatus.of(guarded(() -> accounts.setReportStatus(user, locked)));
        log.info("User {} {} their credit report through the connector", user.userId(), locked ? "locked" : "unlocked");
        return status;
    }

    /** Turns downstream failures into messages for the user; logs them without detail. */
    private static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (AccountClient.TokenRejectedException e) {
            throw new ToolException(RECONNECT);
        } catch (ServiceUnavailableException e) {
            log.warn("Connector tool failed downstream: {}", SafeLogging.describe(e));
            throw new ToolException(UNAVAILABLE);
        }
    }
}
