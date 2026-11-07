package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class SafeLoggingTest {

    @Test
    void listsTheCauseChainWithoutAnyMessages() {
        Exception e = new IllegalStateException(
                "request failed for credit-report://ssn/123456789",
                new UncheckedIOException("io 123456789", new IOException("socket closed")));

        assertThat(SafeLogging.describe(e))
                .isEqualTo("IllegalStateException <- UncheckedIOException <- IOException")
                .doesNotContain("123456789");
    }

    @Test
    void handlesNull() {
        assertThat(SafeLogging.describe(null)).isEmpty();
    }
}
