package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreBandTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "300, RED", "599, RED",
        "600, ORANGE", "650, ORANGE", "700, ORANGE",
        "701, GREEN", "850, GREEN"
    })
    void bandBoundariesMatchTheSpec(int score, ScoreBand expected) {
        assertThat(ScoreBand.of(score)).isEqualTo(expected);
    }

    @Test
    void perfectScoreDrawsAFullCircle() {
        assertThat(CreditScore.of(850).fraction()).isEqualTo(1.0);
        assertThat(CreditScore.of(425).fraction()).isEqualTo(0.5);
        assertThat(CreditScore.of(850).max()).isEqualTo(850);
    }

    @Test
    void scoreOutsideTheScaleIsRejected() {
        assertThatThrownBy(() -> CreditScore.of(851)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditScore.of(299)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandMustAgreeWithScore() {
        assertThatThrownBy(() -> new CreditScore(800, ScoreBand.RED, 850)).isInstanceOf(IllegalArgumentException.class);
    }
}
