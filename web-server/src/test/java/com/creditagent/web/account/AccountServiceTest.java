package com.creditagent.web.account;

import com.creditagent.web.auth.TestLogins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.AuditLog;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.web.alerts.AlertsClient;
import com.creditagent.web.alerts.AlertsService;
import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AuthenticatedUser;
import com.creditagent.web.auth.FakeIdentityVerifier;
import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.JwtService;
import com.creditagent.web.auth.NotOwnSsnException;
import com.creditagent.web.auth.Session;
import com.creditagent.web.auth.SessionEndListener;
import com.creditagent.web.auth.SsnHasher;
import com.creditagent.web.auth.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AccountServiceTest {

    static final String PASSWORD = "correct horse battery staple";
    static final Ssn ADA_SSN = Ssn.parse("123-45-6789");
    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    final InMemoryRepositories.MutableClock clock = new InMemoryRepositories.MutableClock(NOW);
    final InMemoryRepositories.Users users = new InMemoryRepositories.Users();
    final InMemoryRepositories.Sessions sessions = new InMemoryRepositories.Sessions();
    final AuditLog audit = mock(AuditLog.class);
    final AuthService auth = new AuthService(users, sessions, new BCryptPasswordEncoder(4), new SsnHasher(TestSecrets.of("ssn")),
            new FakeIdentityVerifier(), new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), clock),
            Duration.ofMinutes(5), clock, audit);
    final AlertsService alerts = mock(AlertsService.class);
    final ReportEraseClient reports = mock(ReportEraseClient.class);
    final CreditReportSource reportSource = mock(CreditReportSource.class);
    final List<AuthenticatedUser> ended = new ArrayList<>();
    AccountService accounts;
    AuthenticatedUser ada;

    @BeforeEach
    void setUp() {
        SessionEndListener failing = user -> {
            throw new IllegalStateException("advisor down");
        };
        accounts = new AccountService(auth, users, sessions, alerts, reports, reportSource, List.of(failing, ended::add),
                audit, clock);
        auth.register("ada", PASSWORD, "ada@example.com", "+12065550100", "98101", FakeIdentityVerifier.card("123-45-6789"));
        TestLogins.login(auth, "ada", PASSWORD);
        Session session = sessions.byId.values().iterator().next();
        ada = new AuthenticatedUser("ada", session.sessionId(), session.ssnHash(), session.ssnLastFour());
    }

    @Test
    void exportHoldsTheAccountReportAndAlertsButNoSecrets() {
        CreditReport report = new CreditReport("r-1", "***-**-6789", "Ada", NOW, List.of(), 0, 0, 0);
        given(reportSource.findReport(ADA_SSN)).willReturn(Optional.of(report));
        given(alerts.findingsFor("ada@example.com")).willReturn(List.of(new AlertsClient.Finding("loc-a", NOW)));

        AccountService.Export export = accounts.export(ada, ADA_SSN);

        assertThat(export.account().userId()).isEqualTo("ada");
        assertThat(export.account().email()).isEqualTo("ada@example.com");
        assertThat(export.account().mobilePhone()).isEqualTo("+12065550100");
        assertThat(export.account().ssnMasked()).isEqualTo("***-**-6789");
        assertThat(export.account().lastLoginAt()).isEqualTo(NOW);
        assertThat(export.account().consentVersion()).isNotBlank();
        assertThat(export.account().kycVerificationId()).isNotBlank();
        assertThat(export.notes()).contains("audit").contains("30 days");
        assertThat(export.creditReport()).isEqualTo(report);
        assertThat(export.darkWebFindings()).hasSize(1);
        assertThat(export.toString()).doesNotContain("$2").doesNotContain(ada.ssnHash());
        verify(audit).record(AuditLog.Event.ACCOUNT_EXPORTED, "user:ada", "user:ada", "OK");
        verify(audit).record(AuditLog.Event.REPORT_READ, "user:ada", "user:ada", "OK", Map.of("via", "export"));
    }

    @Test
    void exportNeedsTheOwnSsnAnExistingAccountAndWorkingAlerts() {
        assertThatThrownBy(() -> accounts.export(ada, Ssn.parse("234-56-7890"))).isInstanceOf(NotOwnSsnException.class);
        given(reportSource.findReport(ADA_SSN)).willReturn(Optional.empty());
        given(alerts.findingsFor(anyString())).willThrow(new ServiceUnavailableException("down", null));
        assertThatThrownBy(() -> accounts.export(ada, ADA_SSN)).isInstanceOf(ServiceUnavailableException.class);
        users.byId.clear();
        assertThatThrownBy(() -> accounts.export(ada, ADA_SSN)).isInstanceOf(UnauthenticatedException.class);
        verify(audit, never()).record(eq(AuditLog.Event.ACCOUNT_EXPORTED), anyString(), anyString(), anyString());
    }

    @Test
    void deletionErasesEverythingEndsEveryTokenAndFreesTheSsnAndEmail() {
        // A connector grant and its used-code marker, besides the login session.
        auth.openGrant("grant-1", "ada", ADA_SSN, NOW.plusSeconds(60), NOW.plus(Duration.ofHours(1)));

        accounts.delete(ada, PASSWORD, ADA_SSN);

        verify(alerts).erase("ada@example.com");
        verify(reports).erase(ADA_SSN);
        assertThat(users.find("ada")).isEmpty();
        assertThat(sessions.byId.keySet()).containsExactly("code#grant-1"); // keeps the code single use
        assertThat(ended).containsExactly(ada); // the failing listener did not stop the next one
        verify(audit).record(AuditLog.Event.ACCOUNT_DELETED, "user:ada", "user:ada", "OK");
        // The same person can open a new account later.
        auth.register("ada2", PASSWORD, "ada@example.com", "+12065550100", "98101", FakeIdentityVerifier.card("123-45-6789"));
    }

    @Test
    void aWrongPasswordOrSsnDeletesNothing() {
        assertThatThrownBy(() -> accounts.delete(ada, "wrong password entirely", ADA_SSN))
                .isInstanceOf(AccountService.NotTheOwnerException.class);
        assertThatThrownBy(() -> accounts.delete(ada, PASSWORD, Ssn.parse("234-56-7890")))
                .isInstanceOf(AccountService.NotTheOwnerException.class);
        verify(alerts, never()).erase(anyString());
        verify(reports, never()).erase(any());
        assertThat(users.find("ada")).isPresent();
    }

    @Test
    void ifDataElsewhereCannotBeErasedTheAccountStaysSoTheUserCanRetry() {
        willThrow(new ServiceUnavailableException("generator down", null)).given(reports).erase(ADA_SSN);
        assertThatThrownBy(() -> accounts.delete(ada, PASSWORD, ADA_SSN)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(users.find("ada")).isPresent();
        assertThat(sessions.byId).isNotEmpty();

        willThrow(new ServiceUnavailableException("alerts down", null)).given(alerts).erase(anyString());
        assertThatThrownBy(() -> accounts.delete(ada, PASSWORD, ADA_SSN)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(users.find("ada")).isPresent();
    }

    @Test
    void purgeErasesAlertsSessionsAndTheAccount() {
        Instant cutoff = NOW.plusSeconds(1); // ada's last login is NOW: unused since before the cutoff
        assertThat(accounts.purge("nobody", cutoff)).isFalse();
        assertThat(accounts.purge("ada", NOW.minusSeconds(1))).isFalse(); // used since: kept
        verify(alerts, never()).erase(anyString());
        assertThat(accounts.purge("ada", cutoff)).isTrue();
        verify(alerts).erase("ada@example.com");
        verify(reports, never()).erase(any()); // reports age out in the generator's own retention job
        assertThat(users.find("ada")).isEmpty();
        assertThat(sessions.byId).isEmpty();
        verify(audit).record(eq(AuditLog.Event.ACCOUNT_PURGED), eq("job:account-retention"), eq("user:ada"), eq("OK"), any());
    }

    @Test
    void accountsWithoutAnEmailNeedNoAlertErase() {
        users.byId.put("old", new com.creditagent.web.auth.User("old", "$2a$h", null, "h-old", "0000", null, NOW,
                com.creditagent.web.auth.ReportStatus.UNLOCKED));
        assertThat(accounts.purge("old", NOW.plusSeconds(1))).isTrue();
        verify(alerts, never()).erase(anyString());
    }

    @Test
    void aSessionOpenedDuringTheDeletionIsSweptToo() {
        com.creditagent.web.auth.UserRepository racing = new InMemoryRepositories.Users() {
            {
                byId.putAll(users.byId);
            }

            @Override
            public boolean delete(com.creditagent.web.auth.User user) {
                // A login lands between the first sweep and the account's removal.
                sessions.save(new Session("late", user.userId(), user.ssnHash(), user.ssnLastFour(), NOW, NOW, NOW.plusSeconds(300)));
                return super.delete(user);
            }
        };
        new AccountService(auth, racing, sessions, alerts, reports, reportSource, List.of(), audit, clock)
                .delete(ada, PASSWORD, ADA_SSN);
        assertThat(sessions.byId).doesNotContainKey("late");
    }
}
