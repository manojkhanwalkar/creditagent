package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** The application starts with its beans wired (no scan runs: the first one is an hour away). */
@SpringBootTest
@TestPropertySource(properties = {
    "credit.monitor.initial-delay=1h",
    "credit.monitor.dynamodb-endpoint=http://localhost:1",
    "credit.monitor.placeholder-exposed=demo@example.com"
})
class MonitorWiringTest {

    @Autowired
    DarkWebMonitor monitor;

    @Autowired
    DarkWebSource source;

    @Test
    void theMonitorAndThePlaceholderSourceAreWired() {
        assertThat(monitor).isNotNull();
        assertThat(source.exposed("paste-site-alpha", "demo@example.com")).isTrue();
    }
}
