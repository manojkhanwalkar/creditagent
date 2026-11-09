package com.creditagent.agent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CreditIntelligenceAgentExecutorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "123-45-6789|123-45-6789",
        "123456789|123456789",
        "Please check SSN 123-45-6789 thanks|123-45-6789",
        "ssn:123456789.|123456789"
    })
    void findsTheSsnInFreeText(String text, String expected) {
        assertThat(CreditIntelligenceAgentExecutor.extractSsn(text)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no number here", "12345678", "1234567890123", "phone 555-1234"})
    void returnsEmptyWhenThereIsNoSsn(String text) {
        assertThat(CreditIntelligenceAgentExecutor.extractSsn(text)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void cancellingIsNotSupported() {
        CreditIntelligenceAgentExecutor executor = new CreditIntelligenceAgentExecutor(null, tools.jackson.databind.json.JsonMapper.builder().build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.cancel(null, null))
                .isInstanceOf(org.a2aproject.sdk.spec.UnsupportedOperationError.class);
    }
}
