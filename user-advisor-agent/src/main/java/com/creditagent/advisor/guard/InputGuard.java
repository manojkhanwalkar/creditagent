package com.creditagent.advisor.guard;

/**
 * Checks a user's question before it reaches the model or the conversation memory. In order:
 *
 * <ol>
 *   <li>length (empty, or over the limit);
 *   <li>profanity and abuse;
 *   <li>prompt injection: instruction overrides, role play, requests for the system prompt, chat
 *       template markers, and payloads hidden in base64, hex or Morse (OWASP LLM01);
 *   <li>protected characteristics (race, religion, national origin, sex, marital status, …), which
 *       credit decisions may not use;
 *   <li>someone else's credit;
 *   <li>credit fraud (CPNs, synthetic identities, disputing accurate items);
 *   <li>topic: the question has to be about the credit report. Small talk gets a polite answer, and a
 *       short follow-up that points back at the last answer ("why?", "tell me more about that") is
 *       allowed once a conversation has started.
 * </ol>
 *
 * <p>Every check reads {@link TextNormalizer folded} text, so look-alike letters, invisible characters
 * and digit-for-letter spellings do not get past it. An accepted question is passed on cleaned and
 * with personal identifiers {@link PiiRedactor redacted}.
 */
public class InputGuard {

    /** Words in a follow-up that may stand on its own ("why?", "tell me more about that"). */
    static final int MAX_FOLLOW_UP_WORDS = 12;

    /**
     * A question with no credit word is accepted as a follow-up only if it points back at the previous
     * answer with one of these, so "hi" followed by any short request does not get past the topic check.
     */
    static final java.util.Set<String> FOLLOW_UP_CUES = java.util.Set.of(
            "more", "why", "that", "this", "it", "those", "these", "them", "explain", "elaborate", "detail", "details",
            "example", "examples", "mean", "else", "again", "so");

    /** Why a question was refused. The text is what the user is told. */
    public enum Refusal {
        EMPTY("Ask me a question about your credit report."),
        TOO_LONG("Please keep your question shorter."),
        ABUSIVE("Please ask again without offensive language."),
        INJECTION("I can only answer questions about your credit report, and I can't change how I work."),
        PROTECTED("Credit reports and scores don't consider race, colour, religion, national origin, sex, marital status"
                + " or other protected characteristics, so I can't discuss them. Ask me about your accounts, payments or"
                + " inquiries instead."),
        OTHER_PERSON("I can only discuss your own credit report."),
        FRAUD("I can't help with that. If something on your report is wrong, you can dispute it with the credit"
                + " bureau that reports it."),
        OFF_TOPIC("I can only answer questions about your credit report: for example your payment history, card"
                + " balances, hard inquiries, or how to improve your score.");

        private final String message;

        Refusal(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /**
     * The outcome: either {@code refusal} is set, or {@code text} is the question to answer (cleaned
     * and redacted). {@code smallTalk} marks a greeting or thanks.
     */
    public record Verdict(Refusal refusal, String text, boolean smallTalk) {

        public boolean allowed() {
            return refusal == null;
        }

        static Verdict refuse(Refusal refusal) {
            return new Verdict(refusal, null, false);
        }
    }

    private final int maxChars;

    public InputGuard(int maxChars) {
        this.maxChars = maxChars;
    }

    /** @param conversationStarted earlier turns exist, so a short follow-up has context */
    public Verdict check(String prompt, boolean conversationStarted) {
        if (prompt == null || prompt.isBlank()) {
            return Verdict.refuse(Refusal.EMPTY);
        }
        if (prompt.length() > maxChars) {
            return Verdict.refuse(Refusal.TOO_LONG);
        }
        String cleaned = TextNormalizer.clean(prompt);
        if (cleaned.length() > maxChars) {
            // NFKC can expand a character many times over (U+FDFA becomes 18): check the length again.
            return Verdict.refuse(Refusal.TOO_LONG);
        }
        String folded = TextNormalizer.fold(cleaned);
        String leet = TextNormalizer.foldLeet(cleaned);
        if (folded.isEmpty()) {
            return Verdict.refuse(Refusal.EMPTY);
        }
        if (abusive(folded) || abusive(leet) || GuardTerms.MASKED_ABUSE.matcher(cleaned.toLowerCase(java.util.Locale.ROOT)).find()) {
            return Verdict.refuse(Refusal.ABUSIVE);
        }
        String squashed = leet.replace(" ", "");
        if (GuardTerms.INJECTION.matcher(folded).find() || GuardTerms.INJECTION.matcher(leet).find()
                || GuardTerms.SQUASHED_INJECTION.matcher(squashed).find()
                || GuardTerms.ROLE_MARKERS.matcher(cleaned).find() || GuardTerms.ENCODED.matcher(cleaned).find()) {
            return Verdict.refuse(Refusal.INJECTION);
        }
        if (GuardTerms.PROTECTED.matcher(folded).find()) {
            return Verdict.refuse(Refusal.PROTECTED);
        }
        if (GuardTerms.OTHER_PERSON.matcher(folded).find()) {
            return Verdict.refuse(Refusal.OTHER_PERSON);
        }
        if (GuardTerms.FRAUD.matcher(folded).find() || GuardTerms.SQUASHED_FRAUD.matcher(folded.replace(" ", "")).find()) {
            return Verdict.refuse(Refusal.FRAUD);
        }
        String text = PiiRedactor.redact(cleaned);
        if (GuardTerms.SMALL_TALK.contains(folded)) {
            return new Verdict(null, text, true);
        }
        boolean onTopic = GuardTerms.anyTokenStartsWith(folded, GuardTerms.TOPIC_STEMS);
        boolean followUp = conversationStarted && folded.split(" ").length <= MAX_FOLLOW_UP_WORDS
                && GuardTerms.anyToken(folded, FOLLOW_UP_CUES);
        if (!onTopic && !followUp) {
            return Verdict.refuse(Refusal.OFF_TOPIC);
        }
        return new Verdict(null, text, false);
    }

    static boolean abusive(String folded) {
        return GuardTerms.anyTokenStartsWith(folded, GuardTerms.ABUSIVE_STEMS) || GuardTerms.anyToken(folded, GuardTerms.ABUSIVE_WORDS);
    }
}
