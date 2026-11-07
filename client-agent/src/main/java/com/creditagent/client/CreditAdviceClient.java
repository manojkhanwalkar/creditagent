package com.creditagent.client;

import com.creditagent.common.CreditAdvice;
import com.creditagent.common.Ssn;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends the SSN over A2A and turns the agent's JSON reply into a {@link CreditAdvice}. */
public class CreditAdviceClient {

    private final CreditAdvisorPort port;
    private final JsonMapper jsonMapper;

    public CreditAdviceClient(CreditAdvisorPort port, JsonMapper jsonMapper) {
        this.port = Objects.requireNonNull(port);
        this.jsonMapper = Objects.requireNonNull(jsonMapper);
    }

    public CreditAdvice adviceFor(Ssn ssn) {
        String reply;
        try {
            reply = port.requestAdvice(ssn.digits());
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
