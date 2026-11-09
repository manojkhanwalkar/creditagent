package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.Ssn;
import org.junit.jupiter.api.Test;

class ReportLockCheckTest {

    static final Ssn SSN = Ssn.parse("123-45-6789");

    @Test
    void aDecisionFollowsTheLockAndFailsClosed() {
        assertThat(((ReportLockCheck) ssn -> true).decide(SSN)).isEqualTo(ReportLockCheck.Decision.LOCKED);
        assertThat(((ReportLockCheck) ssn -> false).decide(SSN)).isEqualTo(ReportLockCheck.Decision.RELEASE);
        ReportLockCheck down = ssn -> {
            throw new ServiceUnavailableException("web server down", null);
        };
        assertThat(down.decide(SSN)).isEqualTo(ReportLockCheck.Decision.UNAVAILABLE);
    }

    @Test
    void anyOtherFailureIsNotSwallowed() {
        ReportLockCheck buggy = ssn -> {
            throw new IllegalStateException("bug");
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> buggy.decide(SSN)).isInstanceOf(IllegalStateException.class);
    }
}
