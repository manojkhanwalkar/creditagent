package com.creditagent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.CreditAdvice;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CreditAdviceClientTest {

    static final String ADVICE = """
            {"ssnMasked":"***-**-6789","score":640,"band":"ORANGE","summary":"s",
             "recommendations":[{"priority":1,"title":"Pay on time","detail":"d","impact":"HIGH"}],
             "source":"rules"}
            """;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sendsTheDigitsAndParsesTheAdvice() {
        AtomicReference<String> sent = new AtomicReference<>();
        CreditAdviceClient client = new CreditAdviceClient(ssn -> {
            sent.set(ssn);
            return ADVICE;
        }, json);

        CreditAdvice advice = client.adviceFor(Ssn.parse("123-45-6789"));

        assertThat(sent.get()).isEqualTo("123456789");
        assertThat(advice.score()).isEqualTo(640);
        assertThat(advice.band()).isEqualTo(ScoreBand.ORANGE);
        assertThat(advice.recommendations()).hasSize(1);
    }

    @Test
    void agentErrorRepliesBecomeRejections() {
        CreditAdviceClient client = new CreditAdviceClient(ssn -> "{\"error\":\"No credit report was found for that SSN.\"}", json);
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentRejectedException.class)
                .hasMessage("No credit report was found for that SSN.");
    }

    @Test
    void transportFailuresAreWrapped() {
        CreditAdviceClient client = new CreditAdviceClient(ssn -> {
            throw new IllegalStateException("connection refused");
        }, json);
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentCallException.class);
    }

    @Test
    void garbageRepliesAreWrapped() {
        CreditAdviceClient client = new CreditAdviceClient(ssn -> "not json", json);
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentCallException.class);
    }
}
