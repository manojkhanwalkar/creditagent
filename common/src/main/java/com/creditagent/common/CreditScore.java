package com.creditagent.common;

/**
 * A credit score on the 300–850 scale, where {@link #MAX} is a perfect (complete-circle) score.
 *
 * @param value the score
 * @param band the colour band derived from the score
 * @param max the score that draws a full circle
 */
public record CreditScore(int value, ScoreBand band, int max) {

    public static final int MIN = 300;
    public static final int MAX = 850;

    public CreditScore {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException("Score must be between " + MIN + " and " + MAX + ": " + value);
        }
        if (band != ScoreBand.of(value)) {
            throw new IllegalArgumentException("Band " + band + " does not match score " + value);
        }
    }

    public static CreditScore of(int value) {
        return new CreditScore(value, ScoreBand.of(value), MAX);
    }

    /** Fraction of the ring to draw, 0.0 – 1.0. */
    public double fraction() {
        return (double) value / max;
    }
}
