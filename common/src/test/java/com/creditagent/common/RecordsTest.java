package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordsTest {

    @Test
    void adviceCopiesItsRecommendationsAndDefaultsToNone() {
        List<Recommendation> recommendations =
                new ArrayList<>(List.of(new Recommendation(1, "Pay on time", "d", Recommendation.Impact.HIGH)));
        CreditAdvice advice = new CreditAdvice("***-**-6789", 640, ScoreBand.ORANGE, "s", recommendations, "rules");
        recommendations.clear();
        assertThat(advice.recommendations()).hasSize(1);
        assertThat(advice.recommendations().getFirst().impact()).isEqualTo(Recommendation.Impact.HIGH);
        assertThat(new CreditAdvice("***-**-6789", 640, ScoreBand.ORANGE, "s", null, "rules").recommendations()).isEmpty();
    }

    @Test
    void impactsAreOrderedFromHighToLow() {
        assertThat(Recommendation.Impact.values())
                .containsExactly(Recommendation.Impact.HIGH, Recommendation.Impact.MEDIUM, Recommendation.Impact.LOW);
        assertThat(Recommendation.Impact.valueOf("LOW")).isEqualTo(Recommendation.Impact.LOW);
    }

    @Test
    void aValidSsnIsReportedValid() {
        assertThat(Ssn.isValid("123-45-6789")).isTrue();
        assertThat(Ssn.isValid("123456789")).isTrue();
    }

    @Test
    void anExceptionThatIsItsOwnCauseIsDescribedOnce() {
        RuntimeException selfCaused = new RuntimeException("loop") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(SafeLogging.describe(selfCaused)).doesNotContain("<-");
    }
}
