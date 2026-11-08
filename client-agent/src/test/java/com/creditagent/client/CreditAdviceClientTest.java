package com.creditagent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.CreditAdvice;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
import com.creditagent.clients.RetrySettings;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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

    static final RetrySettings FAST =
            new RetrySettings(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(4), Duration.ZERO);

    CreditAdviceClient client(CreditAdvisorPort port, JsonMapper mapper) {
        return new CreditAdviceClient(port, mapper, Duration.ofSeconds(5), FAST);
    }

    @Test
    void sendsTheDigitsAndParsesTheAdvice() {
        AtomicReference<String> sent = new AtomicReference<>();
        CreditAdviceClient client = client(ssn -> {
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
        CreditAdviceClient client = client(ssn -> "{\"error\":\"No credit report was found for that SSN.\"}", json);
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentRejectedException.class)
                .hasMessage("No credit report was found for that SSN.");
    }

    @Test
    void transportFailuresAreWrapped() {
        CreditAdviceClient client = client(ssn -> {
            throw new IllegalStateException("connection refused");
        }, json);
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentCallException.class);
    }

    @Test
    void garbageRepliesAreWrapped() {
        CreditAdviceClient client = client(ssn -> "not json", json);
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentCallException.class);
    }

    @Test
    void anAgentThatIsNotUpYetIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        CreditAdviceClient client = client(ssn -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("card fetch failed", new ConnectException("refused"));
            }
            return ADVICE;
        }, json);

        assertThat(client.adviceFor(Ssn.parse("123-45-6789")).score()).isEqualTo(640);
        assertThat(calls).hasValue(3);
    }

    @Test
    void aFailureAfterTheRequestWasSentIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        CreditAdviceClient client = client(ssn -> {
            calls.incrementAndGet();
            throw new IllegalStateException("reset", new SocketTimeoutException("read timed out"));
        }, json);

        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentCallException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void aHungAgentTimesOutInsteadOfBlockingForever() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        CreditAdviceClient client = new CreditAdviceClient(ssn -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return ADVICE;
        }, json, Duration.ofMillis(200), FAST);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.adviceFor(Ssn.parse("123-45-6789")))
                .isInstanceOf(CreditAdviceClient.AgentCallException.class)
                .hasMessageContaining("did not answer");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(calls).hasValue(1); // a timeout is not retried
        // The abandoned call is interrupted, so its thread and connection are not left blocked.
        assertThat(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
}
