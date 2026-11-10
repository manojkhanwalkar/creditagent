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
    /** On folded text. Not a bare "100": a maxed-out card is "100% utilisation", a fact, not a promise. */
    private static final Pattern PROMISE = Pattern.compile("\\b(guarantee[ds]?|guaranteed|100 percent (sure|certain|guaranteed)"
            + "|(definitely|certainly|surely) (will )?(raise|increase|boost|improve|fix|lift)"
            + "|(will|going to) (definitely |certainly |surely )?(raise|increase|boost|improve|lift) (your score |you |it )?"
            + "(by \\d+ points|to \\d{3})"
            + "|certain to (raise|increase|boost|improve))\\b");
    /** On cleaned text: "100%" next to a promise word ("will 100% fix", "100% sure"). */
    private static final Pattern PERCENT_PROMISE = Pattern.compile(
            "(?i)\\b(will|would|going to)\\s+100\\s*%|\\b100\\s*%\\s+(sure|certain|guaranteed|chance)\\b");
    private static final String SENTINEL_FOLDED = TextNormalizer.fold(PromptMarkers.SENTINEL);
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
                || GuardTerms.anyToken(folded, GuardTerms.OUTPUT_INSULTS)
                || GuardTerms.MASKED_ABUSE.matcher(cleaned.toLowerCase(java.util.Locale.ROOT)).find()) {
            return Problem.OFFENSIVE;
        }
        if (GuardTerms.PROTECTED.matcher(folded).find()) {
            return Problem.PROTECTED;
        }
        if (LEAK.matcher(cleaned).find() || GuardTerms.ROLE_MARKERS.matcher(cleaned).find()
                || folded.contains(SENTINEL_FOLDED)) {
            return Problem.LEAK;
        }
        if (PROMISE.matcher(folded).find() || PERCENT_PROMISE.matcher(cleaned).find()) {
            return Problem.PROMISE;
        }
        return null;
    }
}
