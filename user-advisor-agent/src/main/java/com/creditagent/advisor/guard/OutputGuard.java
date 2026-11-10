package com.creditagent.advisor.guard;

import java.util.regex.Pattern;

/**
 * Checks the model's answer before the user sees it or it is remembered:
 *
 * <ul>
 *   <li>offensive language, insults, and any mention of race, religion, national origin or another
 *       protected characteristic: a credit answer never needs one, so it is replaced, not edited;
 *   <li>leaks of the instructions or of the prompt's delimiters (OWASP LLM07, system prompt leakage);
 *   <li>promises of an outcome ("guaranteed", "100%"), which a credit assistant must not make;
 *   <li>personal identifiers, {@link PiiRedactor redacted};
 *   <li>length, and markup characters the chat shows as text anyway.
 * </ul>
 *
 * <p>Every answer, refused or not, ends with the {@link #DISCLAIMER}.
 */
public class OutputGuard {

    public static final String DISCLAIMER = "This answer is for educational purposes only. It is not financial, legal or"
            + " credit advice. Please consult a qualified professional before taking any action.";
    static final String REPLACEMENT = "I can't give that answer. Please ask me about your accounts, payment history,"
            + " balances or inquiries.";

    /** Why an answer was replaced, for the log (never shown). */
    public enum Problem { OFFENSIVE, PROTECTED, LEAK, PROMISE }

    /** The text to show, with the disclaimer, and what was wrong with the original if it was replaced. */
    public record Result(String reply, String disclaimer, Problem problem) {
        public boolean replaced() {
            return problem != null;
        }
    }

    private static final Pattern LEAK = Pattern.compile(
            "(?i)(</?\\s*(credit_report|user_message|history|instructions)\\b|system prompt|my instructions|"
                    + PromptMarkers.SENTINEL + ")");
    private static final Pattern PROMISE = Pattern.compile("\\b(guarantee[ds]?|guaranteed|100 percent|definitely will raise|certain to raise)\\b");
    private static final Pattern PERCENT_PROMISE = Pattern.compile("\\b100\\s*%");
    private static final Pattern MARKUP = Pattern.compile("[<>]");

    private final int maxChars;

    public OutputGuard(int maxChars) {
        this.maxChars = maxChars;
    }

    public Result check(String reply) {
        String cleaned = TextNormalizer.clean(reply);
        Problem problem = problem(cleaned);
        if (problem != null) {
            return new Result(REPLACEMENT, DISCLAIMER, problem);
        }
        String safe = MARKUP.matcher(PiiRedactor.redact(cleaned)).replaceAll("");
        if (safe.length() > maxChars) {
            safe = safe.substring(0, maxChars - 1).strip() + "…";
        }
        if (safe.isEmpty()) {
            return new Result(REPLACEMENT, DISCLAIMER, Problem.LEAK);
        }
        return new Result(safe, DISCLAIMER, null);
    }

    private static Problem problem(String cleaned) {
        String folded = TextNormalizer.fold(cleaned);
        if (InputGuard.abusive(folded) || InputGuard.abusive(TextNormalizer.foldLeet(cleaned))
                || GuardTerms.MASKED_ABUSE.matcher(cleaned.toLowerCase(java.util.Locale.ROOT)).find()) {
            return Problem.OFFENSIVE;
        }
        if (GuardTerms.PROTECTED.matcher(folded).find()) {
            return Problem.PROTECTED;
        }
        if (LEAK.matcher(cleaned).find() || GuardTerms.ROLE_MARKERS.matcher(cleaned).find()) {
            return Problem.LEAK;
        }
        if (PROMISE.matcher(folded).find() || PERCENT_PROMISE.matcher(cleaned).find()) {
            return Problem.PROMISE;
        }
        return null;
    }
}
