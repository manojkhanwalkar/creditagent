package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SsnHmacTest {

    static final String KEY = "k".repeat(SsnHmac.MIN_KEY_BYTES);

    @Test
    void hashesTheDigitsWhateverTheFormat() {
        SsnHmac hmac = new SsnHmac(KEY, "test key");
        assertThat(hmac.hex(Ssn.parse("123-45-6789"))).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(hmac.hex(Ssn.parse("123456789")));
        assertThat(hmac.matches(Ssn.parse("123456789"), hmac.hex(Ssn.parse("123-45-6789")))).isTrue();
        assertThat(hmac.matches(Ssn.parse("234-56-7890"), hmac.hex(Ssn.parse("123-45-6789")))).isFalse();
        assertThat(hmac.matches(Ssn.parse("123-45-6789"), null)).isFalse();
    }

    @Test
    void differentKeysGiveDifferentHashes() {
        assertThat(new SsnHmac(KEY, "a").hex(Ssn.parse("123-45-6789")))
                .isNotEqualTo(new SsnHmac("j".repeat(32), "b").hex(Ssn.parse("123-45-6789")));
    }

    @Test
    void shortOrMissingKeysAreRefusedWithTheKeysName() {
        assertThatThrownBy(() -> new SsnHmac("short", "SSN HMAC key"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SSN HMAC key must be at least 32 bytes");
        assertThatThrownBy(() -> new SsnHmac(null, "HMAC key")).isInstanceOf(NullPointerException.class);
    }
}
