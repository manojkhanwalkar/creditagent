package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.clients.RetrySettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A caller must wait longer than the service it calls can take, retries included; otherwise it
 * gives up on (and may repeat) work still in progress.
 */
class TimeoutBudgetTest {

    static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void anMcpReadOutlastsTheMcpServersRetriesOfNrg() {
        Duration nrgBudget = RetrySettings.DEFAULTS.worstCase(DOWNSTREAM_TIMEOUT);
        assertThat(WebServerProperties.DEFAULT_MCP_TIMEOUT).isGreaterThan(nrgBudget);
    }
}
