package com.creditagent.common;

/**
 * Colour band used by the UI score ring.
 *
 * <ul>
 *   <li>{@link #GREEN}: above 700
 *   <li>{@link #ORANGE}: 600 to 700 inclusive
 *   <li>{@link #RED}: below 600
 * </ul>
 */
public enum ScoreBand {
    RED,
    ORANGE,
    GREEN;

    public static ScoreBand of(int score) {
        if (score > 700) {
            return GREEN;
        }
        if (score >= 600) {
            return ORANGE;
        }
        return RED;
    }
}
