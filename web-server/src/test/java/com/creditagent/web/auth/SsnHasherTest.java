package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.Ssn;
import org.junit.jupiter.api.Test;

class SsnHasherTest {

    final SsnHasher hasher = new SsnHasher(TestSecrets.of("ssn"));

    @Test
    void sameSsnSameHashWhateverTheFormat() {
        assertThat(hasher.hash(Ssn.parse("123-45-6789"))).isEqualTo(hasher.hash(Ssn.parse("123456789")))
                .hasSize(64).doesNotContain("6789");
        assertThat(hasher.matches(Ssn.parse("123456789"), hasher.hash(Ssn.parse("123-45-6789")))).isTrue();
    }

    @Test
    void differentSsnOrKeyDoesNotMatch() {
        String stored = hasher.hash(Ssn.parse("123-45-6789"));
        assertThat(hasher.matches(Ssn.parse("234-56-7890"), stored)).isFalse();
        assertThat(new SsnHasher(TestSecrets.of("other")).matches(Ssn.parse("123-45-6789"), stored)).isFalse();
        assertThat(hasher.matches(Ssn.parse("123-45-6789"), null)).isFalse();
    }

    @Test
    void refusesAShortKey() {
        assertThatThrownBy(() -> new SsnHasher("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
