package com.creditagent.client;

import com.creditagent.clients.Retries;
import com.creditagent.clients.RetrySettings;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.Ssn;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the SSN over A2A and turns the agent's JSON reply into a {@link CreditAdvice}.
 *
 * <p>The A2A client waits for the agent's answer with no limit of its own, so each call is bounded
 * here by {@code timeout}. A call is retried with backoff ({@link RetrySettings}) only when the
 * failure proves the agent never received it (connection refused, unknown host): the agent may be
 * running a slow LLM call, and a retry after a timeout would only double that work.
 */
public class CreditAdviceClient {

    private final CreditAdvisorPort port;
    private final JsonMapper jsonMapper;
    private final Duration timeout;
    private final RetrySettings retry;

    public CreditAdviceClient(CreditAdvisorPort port, JsonMapper jsonMapper, Duration timeout, RetrySettings retry) {
        this.port = Objects.requireNonNull(port);
        this.jsonMapper = Objects.requireNonNull(jsonMapper);
        this.timeout = Objects.requireNonNull(timeout);
        this.retry = Objects.requireNonNull(retry);
    }

    public CreditAdvice adviceFor(Ssn ssn) {
        String reply;
        try {
            reply = Retries.call(retry, Retries::notDelivered, () -> requestWithTimeout(ssn.digits()));
        } catch (AgentCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AgentCallException("Credit Intelligence Agent could not be reached", e);
        }
        try {
            JsonNode node = jsonMapper.readTree(reply);
            if (node.hasNonNull("error")) {
                throw new AgentRejectedException(node.get("error").asString());
            }
            return jsonMapper.treeToValue(node, CreditAdvice.class);
        } catch (JacksonException e) {
            throw new AgentCallException("Credit Intelligence Agent sent an unreadable reply", e);
        }
    }

    private String requestWithTimeout(String ssnDigits) {
        CompletableFuture<String> call = CompletableFuture.supplyAsync(
                () -> port.requestAdvice(ssnDigits), task -> Thread.ofVirtual().name("a2a-call").start(task));
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new AgentCallException("Credit Intelligence Agent did not answer within " + timeout, e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentCallException("Interrupted while waiting for the Credit Intelligence Agent", e);
        } catch (ExecutionException e) {
            // Keep the original cause chain: the retry decision depends on it.
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        }
    }

    public static class AgentCallException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AgentCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The agent answered, but with an error (unknown SSN, invalid input, downstream outage). */
    public static class AgentRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AgentRejectedException(String message) {
            super(message);
        }
    }
}
