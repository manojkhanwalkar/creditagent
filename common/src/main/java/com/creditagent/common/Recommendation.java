package com.creditagent.common;

/**
 * One action the consumer can take to raise their score.
 *
 * @param priority 1 is most important
 * @param impact rough expected effect on the score
 */
public record Recommendation(int priority, String title, String detail, Impact impact) {

    public enum Impact {
        HIGH,
        MEDIUM,
        LOW
    }
}
