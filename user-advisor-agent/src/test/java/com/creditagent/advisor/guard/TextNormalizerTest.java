package com.creditagent.advisor.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void cleanDropsInvisibleAndControlCharactersAndCollapsesSpace() {
        assertThat(TextNormalizer.clean("  my​ cre⁠dit\u0007 \n\n score  ")).isEqualTo("my credit score");
        assertThat(TextNormalizer.clean(null)).isEmpty();
        assertThat(TextNormalizer.clean("ﬁle")).isEqualTo("file"); // ligature, NFKC
    }

    @Test
    void foldMapsLookAlikesAndAccentsToPlainLatin() {
        assertThat(TextNormalizer.fold("Іgnоrе")).isEqualTo("ignore");        // Cyrillic
        assertThat(TextNormalizer.fold("ΑΡΙ")).isEqualTo("api");              // Greek capitals lower-cased first
        assertThat(TextNormalizer.fold("Crédit Scöre!")).isEqualTo("credit score");
        assertThat(TextNormalizer.fold("ＣＲＥＤＩＴ")).isEqualTo("credit");     // full width
        assertThat(TextNormalizer.fold("someone else's")).isEqualTo("someone else s");
    }

    @Test
    void foldLeetUndoesDigitSpellings() {
        assertThat(TextNormalizer.foldLeet("1gn0r3 pr3v10u5")).isEqualTo("ignore previous");
        assertThat(TextNormalizer.foldLeet("$h!t")).isEqualTo("shit");
        assertThat(TextNormalizer.fold("score 720")).isEqualTo("score 720");
    }
}
