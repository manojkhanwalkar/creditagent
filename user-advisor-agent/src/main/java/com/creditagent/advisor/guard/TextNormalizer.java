package com.creditagent.advisor.guard;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Puts text into one canonical form before any guardrail looks at it, so look-alike tricks do not
 * slip past a word list: Unicode compatibility forms (full-width letters, ligatures), invisible
 * characters, look-alike letters from other scripts, and digit-for-letter spellings.
 *
 * <p>Only the guardrails see the folded text. What the user typed is what is answered.
 */
public final class TextNormalizer {

    /** Zero-width and bidirectional controls, soft hyphen, word joiner, BOM: invisible, used to split words. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u180E\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9 ]");
    /** Three or more single characters in a row: "i g n o r e", or "i.g.n.o.r.e" once dots are spaces. */
    private static final Pattern SPACED_LETTERS = Pattern.compile("\\b[a-z0-9](?: [a-z0-9]\\b){2,}");

    /** Cyrillic and Greek letters drawn like Latin ones. */
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('с', 'c'),
            Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ј', 'j'), Map.entry('ѕ', 's'),
            Map.entry('һ', 'h'), Map.entry('ԁ', 'd'), Map.entry('ӏ', 'l'), Map.entry('к', 'k'), Map.entry('м', 'm'),
            Map.entry('т', 't'), Map.entry('в', 'b'), Map.entry('н', 'h'),
            Map.entry('α', 'a'), Map.entry('ε', 'e'), Map.entry('ο', 'o'), Map.entry('ρ', 'p'), Map.entry('ι', 'i'),
            Map.entry('κ', 'k'), Map.entry('ν', 'v'), Map.entry('τ', 't'), Map.entry('υ', 'u'), Map.entry('χ', 'x'),
            Map.entry('ı', 'i'), Map.entry('օ', 'o'), Map.entry('ս', 'u'), Map.entry('ց', 'g'), Map.entry('ɡ', 'g'));

    /** Digits and symbols written in place of letters. */
    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '9', 'g', '@', 'a', '$', 's', '!', 'i');

    private TextNormalizer() {}

    /**
     * NFKC, invisible and control characters removed, whitespace collapsed. Safe to keep and show:
     * what the guardrails pass on to the model.
     */
    public static String clean(String text) {
        String nfkc = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        String visible = CONTROL.matcher(INVISIBLE.matcher(nfkc).replaceAll("")).replaceAll(" ");
        return SPACES.matcher(visible).replaceAll(" ").strip();
    }

    /**
     * {@link #clean}, then lower case, look-alike letters mapped to Latin, accents dropped,
     * everything but letters, digits and spaces turned into spaces, and runs of single letters joined
     * ({@code i g n o r e} → {@code ignore}). For matching phrases.
     */
    public static String fold(String text) {
        String lower = clean(text).toLowerCase(Locale.ROOT);
        StringBuilder mapped = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            mapped.append(HOMOGLYPHS.getOrDefault(c, c));
        }
        String noAccents = Normalizer.normalize(mapped, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String words = SPACES.matcher(NOT_WORD.matcher(noAccents).replaceAll(" ")).replaceAll(" ").strip();
        return SPACED_LETTERS.matcher(words).replaceAll(m -> m.group().replace(" ", ""));
    }

    /**
     * {@link #fold} with digit-for-letter spellings undone ({@code sh1t} → {@code shit}), for word lists.
     * Not for phrases with real numbers in them.
     */
    public static String foldLeet(String text) {
        String lower = clean(text).toLowerCase(Locale.ROOT);
        StringBuilder mapped = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            mapped.append(LEET.getOrDefault(c, c));
        }
        return fold(mapped.toString());
    }
}
