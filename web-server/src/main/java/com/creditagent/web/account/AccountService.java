package com.creditagent.web.account;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.common.AuditLog;
import com.creditagent.common.AuditLog.Event;
import com.creditagent.common.CreditReport;
import com.creditagent.common.SafeLogging;
import com.creditagent.common.Ssn;
import com.creditagent.web.alerts.AlertsClient;
import com.creditagent.web.alerts.AlertsService;
import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AuthenticatedUser;
import com.creditagent.web.auth.ReportStatus;
import com.creditagent.web.auth.SessionEndListener;
import com.creditagent.web.auth.SessionRepository;
import com.creditagent.web.auth.User;
import com.creditagent.web.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The account holder's data rights: a copy of everything held about them (access and portability:
 * CCPA §1798.100/.110, GDPR Art. 15/20) and deleting the account with all its data (CCPA §1798.105,
 * GDPR Art. 17, GLBA disposal); and the retention purge of inactive accounts.
 *
 * <p>Deletion erases the data held elsewhere first (dark-web alerts, stored bureau reports), then the
 * sessions and connector grants, then the account itself. If an outside erase fails, the account is
 * left in place and the user is asked to try again: every step is idempotent, so a retry finishes
 * the job, and an account never disappears while its data stays behind.
 */
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** Wrong password or SSN: one answer for both, like login. */
    public static class NotTheOwnerException extends RuntimeException {
        public NotTheOwnerException() {
            super("Your password or Social Security number is not correct.");
        }
    }

    /** Everything held about the user, as the export returns it. No hashes, keys or other users' data. */
    public record Export(Instant exportedAt, Account account, CreditReport creditReport, List<AlertsClient.Finding> darkWebFindings,
            String notes) {}

    /** The account's stored fields, minus secrets (password hash) and keyed hashes. */
    public record Account(String userId, String email, String zip, String ssnMasked, Instant createdAt, Instant lastLoginAt,
            ReportStatus reportStatus, Integer lastScore, Instant lastScoreAt, String consentVersion, Instant consentedAt) {}

    static final String EXPORT_NOTES = "This is all the personal data CreditAgent holds about you. Your Social Security"
            + " number is stored only as a keyed hash and its last four digits; sessions and connector grants hold the"
            + " same. Chat conversations are kept in memory for at most 5 minutes and are not included.";

    private final AuthService auth;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final AlertsService alerts;
    private final ReportEraseClient reports;
    private final CreditReportSource reportSource;
    private final List<SessionEndListener> sessionEndListeners;
    private final AuditLog audit;
    private final Clock clock;

    public AccountService(AuthService auth, UserRepository users, SessionRepository sessions, AlertsService alerts,
            ReportEraseClient reports, CreditReportSource reportSource, List<SessionEndListener> sessionEndListeners,
            AuditLog audit, Clock clock) {
        this.auth = Objects.requireNonNull(auth);
        this.users = Objects.requireNonNull(users);
        this.sessions = Objects.requireNonNull(sessions);
        this.alerts = Objects.requireNonNull(alerts);
        this.reports = Objects.requireNonNull(reports);
        this.reportSource = Objects.requireNonNull(reportSource);
        this.sessionEndListeners = List.copyOf(sessionEndListeners);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Everything held about the signed-in user. Needs their own SSN (only its keyed hash is stored, and
     * the credit report is fetched by SSN).
     *
     * @throws com.creditagent.web.auth.NotOwnSsnException if the SSN is not theirs
     * @throws com.creditagent.clients.ServiceUnavailableException if the report or alerts cannot be read;
     *     an export is never silently incomplete
     */
    public Export export(AuthenticatedUser user, Ssn ssn) {
        auth.requireOwnSsn(user, ssn);
        User account = auth.account(user.userId())
                .orElseThrow(() -> new UnauthenticatedException("Your session has ended. Please log in again."));
        CreditReport report = reportSource.findReport(ssn).orElse(null);
        auth.auditReportRead(user, "export");
        List<AlertsClient.Finding> findings = account.email() == null ? List.of() : alerts.findingsFor(account.email());
        audit.record(Event.ACCOUNT_EXPORTED, "user:" + user.userId(), "user:" + user.userId(), "OK");
        return new Export(clock.instant(), new Account(account.userId(), account.email(), account.zip(),
                AuthenticatedUser.mask(account.ssnLastFour()), account.createdAt(), account.lastLoginAt(),
                account.reportStatus(), account.lastScore(), account.lastScoreAt(), account.consentVersion(),
                account.consentedAt()), report, findings, EXPORT_NOTES);
    }

    /**
     * Deletes the signed-in user's account and all their data. The password and SSN are asked again,
     * so a stolen session alone cannot delete an account.
     *
     * @throws NotTheOwnerException if the password or SSN is wrong
     * @throws com.creditagent.clients.ServiceUnavailableException if the alerts or reports could not be
     *     erased; nothing of the account is deleted then, and the user can try again
     */
    public void delete(AuthenticatedUser user, String password, Ssn ssn) {
        auth.verifyOwner(user.userId(), password, ssn).orElseThrow(NotTheOwnerException::new);
        User account = auth.account(user.userId()).orElseThrow(NotTheOwnerException::new);
        if (account.email() != null) {
            alerts.erase(account.email());
        }
        reports.erase(ssn);
        removeAccount(account);
        for (SessionEndListener listener : sessionEndListeners) {
            try {
                listener.sessionEnded(user);
            } catch (RuntimeException e) {
                // The account is gone; whatever the listener holds expires on its own.
                log.warn("Session end listener failed: {}", SafeLogging.describe(e));
            }
        }
        audit.record(Event.ACCOUNT_DELETED, "user:" + account.userId(), "user:" + account.userId(), "OK");
        log.info("User {} deleted their account", account.userId());
    }

    /**
     * Deletes an account unused for longer than the retention period (the retention job). Its alerts
     * are erased here; its stored reports are purged by the generator's own retention job, since only
     * the SSN's hash is kept here and the reports are keyed differently.
     *
     * @return false if the account no longer existed
     * @throws com.creditagent.clients.ServiceUnavailableException if the alerts could not be erased
     */
    public boolean purge(String userId) {
        User account = users.find(userId).orElse(null);
        if (account == null) {
            return false;
        }
        if (account.email() != null) {
            alerts.erase(account.email());
        }
        removeAccount(account);
        audit.record(Event.ACCOUNT_PURGED, "job:account-retention", "user:" + userId, "OK",
                Map.of("lastActiveAt", account.lastActiveAt()));
        return true;
    }

    /** Sessions and grants first, so no token outlives the account; then the account and its claims. */
    private void removeAccount(User account) {
        sessions.deleteAllForUser(account.userId());
        users.delete(account);
    }
}
