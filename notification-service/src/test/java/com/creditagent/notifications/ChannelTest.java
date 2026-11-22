package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChannelTest {

    @Test
    void destinationsAreMaskedToBeRecognisableButNotLearnable() {
        assertThat(Channel.EMAIL.mask("ada@example.com")).isEqualTo("a***@example.com");
        assertThat(Channel.EMAIL.mask("@example.com")).isEqualTo("***");
        assertThat(Channel.EMAIL.mask("")).isEmpty();
        assertThat(Channel.EMAIL.mask(null)).isEmpty();
        assertThat(Channel.SMS.mask("+15555550100")).isEqualTo("+1******0100");
        assertThat(Channel.SMS.mask("+447700900123")).isEqualTo("+4*******0123");
        assertThat(Channel.SMS.mask("+1234")).isEqualTo("+****");
    }

    @Test
    void eachChannelAcceptsOnlyItsOwnKindOfAddress() {
        assertThat(Channel.EMAIL.accepts("ada@example.com")).isTrue();
        assertThat(Channel.EMAIL.accepts("a..b@example.com")).isTrue(); // the registration rule
        assertThat(Channel.EMAIL.accepts("not-an-email")).isFalse();
        assertThat(Channel.EMAIL.accepts("+15555550100")).isFalse();
        assertThat(Channel.SMS.accepts("+15555550100")).isTrue();
        assertThat(Channel.SMS.accepts("15555550100")).isFalse();
        assertThat(Channel.SMS.accepts("+05555550100")).isFalse();
        assertThat(Channel.SMS.accepts("+1555555010012345")).isFalse();
        assertThat(Channel.SMS.accepts("+1 555 555 0100")).isFalse();
        assertThat(Channel.SMS.accepts(null)).isFalse();
    }
}
