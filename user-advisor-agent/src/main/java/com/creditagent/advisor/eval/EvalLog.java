package com.creditagent.advisor.eval;

/**
 * Where each chat turn is recorded for offline evaluation (evals of answer quality and of the
 * guardrails). Implementations must never block or fail the chat.
 */
@FunctionalInterface
public interface EvalLog {

    /** Records nothing; for when the eval log is turned off. */
    EvalLog NONE = record -> { };

    void record(EvalRecord record);
}
