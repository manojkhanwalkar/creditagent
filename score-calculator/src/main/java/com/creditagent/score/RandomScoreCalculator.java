package com.creditagent.score;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Placeholder scoring model required by the spec: a random score between 400 and 850 inclusive.
 * Replace with a real model behind the {@link ScoreCalculator} interface.
 */
public class RandomScoreCalculator implements ScoreCalculator {

    static final int LOWEST = 400;
    static final int HIGHEST = 850;

    private final RandomGenerator random;

    public RandomScoreCalculator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public CreditScore calculate(CreditReport report) {
        Objects.requireNonNull(report, "report");
        return CreditScore.of(random.nextInt(LOWEST, HIGHEST + 1));
    }
}
