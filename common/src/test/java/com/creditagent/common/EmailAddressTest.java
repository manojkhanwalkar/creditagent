package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailAddressTest {

    @Test
    void acceptsWhatRegistrationAlwaysAcceptedAndRefusesTheRest() {
        assertThat(EmailAddress.isValid("ada@example.com")).isTrue();
        assertThat(EmailAddress.isValid("a..b@example.com")).isTrue(); // not RFC-clean, but registrable
        assertThat(EmailAddress.isValid(null)).isFalse();
        assertThat(EmailAddress.isValid("no-at-sign")).isFalse();
        assertThat(EmailAddress.isValid("a b@example.com")).isFalse();
        assertThat(EmailAddress.isValid("a@" + "x".repeat(250) + ".io")).isFalse();
    }
}
