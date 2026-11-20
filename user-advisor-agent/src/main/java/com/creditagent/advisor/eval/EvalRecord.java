package com.creditagent.advisor.eval;

import com.creditagent.advisor.guard.PiiRedactor;
import com.creditagent.advisor.guard.TextNormalizer;
import java.time.Instant;

/**
 * One chat turn, as an eval sees it. Every text field is redacted of identifiers (SSNs, card and
 * account numbers, emails, phones) before it is built; the credit report is never included.
 *
 * @param conversation the conversation id (already a hash of the login session), so turns of one
 *     conversation can be grouped without identifying the user
 * @param turn how many turns the conversation had before this one
 * @param question what the user asked
 * @param inputVerdict {@code ALLOWED}, {@code SMALL_TALK}, or the input guardrail's refusal
 * @param modelAnswer what the model said, before the output guardrails; null if it was not asked
 * @param shownAnswer what the user was shown (without the disclaimer)
 * @param outputVerdict {@code PASSED}, the output guardrail's problem, or null if the model was not asked
 * @param model the model that answered
 * @param latencyMillis time spent in the model, or 0 if it was not asked
 */
public record EvalRecord(
        Instant at,
        String conversation,
        int turn,
        String question,
        String inputVerdict,
        String modelAnswer,
        String shownAnswer,
        String outputVerdict,
        String model,
        long latencyMillis) {

    /** Longest text one field keeps: a refused, over-long question needs no more to be evaluated. */
    static final int MAX_FIELD_CHARS = 4_000;

    /** Cleaned, redacted and capped at {@link #MAX_FIELD_CHARS}, ready for the log; null stays null. */
    public static String safe(String text) {
        if (text == null) {
            return null;
        }
        // Capped before cleaning too: normalisation can expand a character many times (U+FDFA).
        String bounded = text.length() > MAX_FIELD_CHARS ? text.substring(0, MAX_FIELD_CHARS) : text;
        String clean = PiiRedactor.redact(TextNormalizer.clean(bounded));
        return clean.length() > MAX_FIELD_CHARS ? clean.substring(0, MAX_FIELD_CHARS) + "…" : clean;
    }

    @Override
    public String toString() {
        return "EvalRecord[conversation=" + conversation + ", turn=" + turn + ", inputVerdict=" + inputVerdict
                + ", outputVerdict=" + outputVerdict + "]";
    }
}
