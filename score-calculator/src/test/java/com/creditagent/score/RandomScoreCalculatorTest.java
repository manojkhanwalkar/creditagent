package com.creditagent.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.ScoreBand;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomScoreCalculatorTest {

    static final CreditReport REPORT = new CreditReport("r", "***-**-6789", "A B", Instant.EPOCH, List.of(), 0, 0, 0);

    @Test
    void scoresStayWithin400And850AndCoverEveryBand() {
        RandomScoreCalculator calculator = new RandomScoreCalculator(new Random(42));
        Set<ScoreBand> bands = EnumSet.noneOf(ScoreBand.class);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 20_000; i++) {
            CreditScore score = calculator.calculate(REPORT);
            min = Math.min(min, score.value());
            max = Math.max(max, score.value());
            bands.add(score.band());
        }
        assertThat(min).isEqualTo(400);
        assertThat(max).isEqualTo(850);
        assertThat(bands).containsExactlyInAnyOrder(ScoreBand.values());
    }

    @Test
    void requiresAReport() {
        assertThatThrownBy(() -> new RandomScoreCalculator(new Random()).calculate(null))
                .isInstanceOf(NullPointerException.class);
    }
}
