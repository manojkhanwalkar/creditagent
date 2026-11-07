package com.creditagent.common;

import java.util.List;

/**
 * What the Credit Intelligence Agent sends back over A2A.
 *
 * @param ssnMasked last four digits only
 * @param source {@code rules} or {@code llm}, so callers know how the advice was produced
 */
public record CreditAdvice(
        String ssnMasked, int score, ScoreBand band, String summary, List<Recommendation> recommendations, String source) {

    public CreditAdvice {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
