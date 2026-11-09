package com.creditagent.web.auth;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionSweeperTest {

    final AuthService auth = mock(AuthService.class);
    final SessionSweeper sweeper = new SessionSweeper(auth);

    @Test
    void sweepsExpiredSessions() {
        given(auth.cleanUpExpiredSessions()).willReturn(3, 0);
        sweeper.sweep();
        sweeper.sweep();
        verify(auth, times(2)).cleanUpExpiredSessions();
    }

    @Test
    void aFailedSweepIsLoggedAndTheSchedulerKeepsRunning() {
        given(auth.cleanUpExpiredSessions()).willThrow(new IllegalStateException("DynamoDB down"));
        Assertions.assertThatCode(sweeper::sweep).doesNotThrowAnyException();
    }
}
