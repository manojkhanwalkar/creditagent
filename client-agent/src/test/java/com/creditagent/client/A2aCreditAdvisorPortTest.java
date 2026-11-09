package com.creditagent.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class A2aCreditAdvisorPortTest {

    @Test
    void anUnreachableAgentFailsEachCallAndIsTriedAgainNextTime() {
        // Nothing listens on port 1: building the A2A client (which fetches the agent card) fails,
        // and because the failed client is not kept, the next call tries again.
        A2aCreditAdvisorPort port = new A2aCreditAdvisorPort("http://127.0.0.1:1");
        assertThatThrownBy(() -> port.requestAdvice("123456789")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> port.requestAdvice("123456789")).isInstanceOf(RuntimeException.class);
    }
}
