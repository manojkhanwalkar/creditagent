package com.creditagent.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ScoreService;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreditToolsTest {

    static final Ssn SSN = Ssn.parse("123-45-6789");
    static final ConnectorUser USER = new ConnectorUser("avery", SSN, "token", "https://c/mcp", "credit");
    static final CreditReport REPORT = new CreditReport("r-1", "***-**-6789", "Avery Doe", Instant.EPOCH, List.of(), 0, 0, 0);

    final CreditReportSource reports = mock(CreditReportSource.class);
    final ScoreService scores = mock(ScoreService.class);
    final AdviceClient advice = mock(AdviceClient.class);
    final AccountClient accounts = mock(AccountClient.class);
    final CreditTools tools = new CreditTools(reports, scores, advice, accounts);

    @Test
    void readsTheUsersOwnReportAndScore() {
        given(reports.findReport(SSN)).willReturn(Optional.of(REPORT));
        given(scores.score(REPORT)).willReturn(CreditScore.of(640));
        assertThat(tools.report(USER)).isEqualTo(REPORT);
        assertThat(tools.score(USER)).isEqualTo(new CreditTools.Score("***-**-6789", 640, 850, ScoreBand.ORANGE));
    }

    @Test
    void noReportOrAnOutageIsToldPlainly() {
        given(reports.findReport(SSN)).willReturn(Optional.empty());
        assertThatThrownBy(() -> tools.score(USER)).hasMessage(CreditTools.NO_REPORT);

        given(reports.findReport(SSN)).willThrow(new ServiceUnavailableException("down 123-45-6789", null));
        assertThatThrownBy(() -> tools.report(USER)).isInstanceOf(CreditTools.ToolException.class).hasMessage(CreditTools.UNAVAILABLE);
    }

    @Test
    void adviceExplainsALockedReport() {
        CreditAdvice answer = new CreditAdvice("***-**-6789", 640, ScoreBand.ORANGE, "ok", List.of(), "rules");
        given(advice.adviceFor(SSN)).willReturn(answer);
        assertThat(tools.advice(USER)).isEqualTo(answer);

        given(advice.adviceFor(SSN)).willThrow(new AdviceClient.AdviceRefusedException(ReportLockCheck.LOCKED_MESSAGE));
        assertThatThrownBy(() -> tools.advice(USER)).hasMessage(CreditTools.LOCKED_ADVICE);

        willThrow(new AdviceClient.AdviceRefusedException("No credit report on file")).given(advice).adviceFor(SSN);
        assertThatThrownBy(() -> tools.advice(USER)).hasMessage(CreditTools.ADVICE_REFUSED);
    }

    @Test
    void readsAndSetsTheLock() {
        given(accounts.reportStatus(USER)).willReturn("LOCKED");
        given(accounts.setReportStatus(USER, false)).willReturn("UNLOCKED");
        assertThat(tools.lockStatus(USER)).isEqualTo(new CreditTools.LockStatus("LOCKED", true));
        assertThat(tools.setLock(USER, false)).isEqualTo(new CreditTools.LockStatus("UNLOCKED", false));
    }

    @Test
    void aRevokedTokenAsksTheUserToReconnect() {
        given(accounts.reportStatus(USER)).willThrow(new AccountClient.TokenRejectedException());
        assertThatThrownBy(() -> tools.lockStatus(USER)).hasMessage(CreditTools.RECONNECT);
    }

    @Test
    void theUserPrintsWithoutTheirSsnOrToken() {
        assertThat(USER.toString()).doesNotContain("6789").doesNotContain("token");
        assertThat(USER.hasScope("credit")).isTrue();
        assertThat(new ConnectorUser("a", SSN, "t", "r", null).hasScope("credit")).isFalse();
        assertThat(new ConnectorUser("a", SSN, "t", "r", "other credit").hasScope("credit")).isTrue();
    }
}
