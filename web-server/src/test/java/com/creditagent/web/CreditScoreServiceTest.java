package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CreditScoreServiceTest {

    static final Ssn SSN = Ssn.parse("123-45-6789");
    static final CreditReport REPORT = new CreditReport("r-1", "***-**-6789", "A B", Instant.EPOCH, List.of(), 0, 0, 0);

    @Test
    void fetchesTheReportThenScoresIt() {
        AtomicReference<CreditReport> scored = new AtomicReference<>();
        CreditScoreService service = new CreditScoreService(
                ssn -> ssn.equals(SSN) ? Optional.of(REPORT) : Optional.empty(),
                report -> {
                    scored.set(report);
                    return CreditScore.of(615);
                });

        assertThat(service.scoreFor(SSN).value()).isEqualTo(615);
        assertThat(scored.get()).isSameAs(REPORT);
    }

    @Test
    void missingReportIsReportedAndNotScored() {
        CreditScoreService service = new CreditScoreService(ssn -> Optional.empty(), report -> {
            throw new AssertionError("should not score");
        });
        assertThatThrownBy(() -> service.scoreFor(SSN)).isInstanceOf(CreditScoreService.CreditReportNotFoundException.class);
    }
}
