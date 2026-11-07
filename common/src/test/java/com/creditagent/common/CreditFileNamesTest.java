package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CreditFileNamesTest {

    private static final String KEY = "test-key-0123456789abcdef0123456789abcdef";

    @Test
    void sameSsnAndKeyGiveTheSameName() {
        Ssn ssn = Ssn.parse("123-45-6789");
        assertThat(new CreditFileNames(KEY).fileNameFor(ssn)).isEqualTo(new CreditFileNames(KEY).fileNameFor(ssn));
    }

    @Test
    void nameIsHexOnlyAndDoesNotContainTheSsn() {
        String name = new CreditFileNames(KEY).fileNameFor(Ssn.parse("123-45-6789"));
        assertThat(name).matches("^[0-9a-f]{64}\\.json$").doesNotContain("6789");
    }

    @Test
    void differentKeysGiveDifferentNames() {
        Ssn ssn = Ssn.parse("123-45-6789");
        assertThat(new CreditFileNames(KEY).fileNameFor(ssn))
                .isNotEqualTo(new CreditFileNames(KEY + "x").fileNameFor(ssn));
    }

    @Test
    void differentSsnsGiveDifferentNames() {
        CreditFileNames names = new CreditFileNames(KEY);
        assertThat(names.fileNameFor(Ssn.parse("123-45-6789"))).isNotEqualTo(names.fileNameFor(Ssn.parse("123-45-6788")));
    }

    @Test
    void shortKeysAreRejected() {
        assertThatThrownBy(() -> new CreditFileNames("too-short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreditFileNames(null)).isInstanceOf(NullPointerException.class);
    }
}
