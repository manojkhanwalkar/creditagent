package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SsnTest {

    @ParameterizedTest
    @ValueSource(strings = {"123-45-6789", "123456789", "  123-45-6789  ", "12345-6789", "123-456789"})
    void parsesDashedAndUndashedForms(String raw) {
        assertThat(Ssn.parse(raw).digits()).isEqualTo("123456789");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12-345-6789", "1234567890", "abc-de-fghi", "123 45 6789", "123-45-678", "１２３-45-6789"})
    void rejectsMalformedValues(String raw) {
        assertThatThrownBy(() -> Ssn.parse(raw)).isInstanceOf(InvalidSsnException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"000-12-3456", "666-12-3456", "900-12-3456", "999-12-3456", "123-00-4567", "123-45-0000"})
    void rejectsNumbersTheSsaNeverIssues(String raw) {
        assertThat(Ssn.isValid(raw)).isFalse();
    }

    @Test
    void errorMessagesNeverContainTheInput() {
        String raw = "666-12-3456";
        assertThatThrownBy(() -> Ssn.parse(raw)).hasMessageNotContaining("666").hasMessageNotContaining("3456");
    }

    @Test
    void toStringIsMaskedSoSsnsDoNotLeakIntoLogs() {
        Ssn ssn = Ssn.parse("123-45-6789");
        assertThat(ssn.toString()).isEqualTo("***-**-6789").doesNotContain("12345");
        assertThat(ssn.masked()).isEqualTo("***-**-6789");
    }

    @Test
    void equalityIsByDigits() {
        assertThat(Ssn.parse("123-45-6789")).isEqualTo(Ssn.parse("123456789")).hasSameHashCodeAs(Ssn.parse("123456789"));
        assertThat(Ssn.parse("123-45-6789")).isNotEqualTo(Ssn.parse("123-45-6780"));
    }
}
