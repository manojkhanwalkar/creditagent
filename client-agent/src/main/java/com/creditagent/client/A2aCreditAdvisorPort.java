package com.creditagent.client;

import dev.langchain4j.agentic.AgenticServices;
import java.util.Objects;

/**
 * A2A implementation of {@link CreditAdvisorPort}. Building the LangChain4j A2A client fetches the
 * remote agent card, so it happens on first use rather than at start-up; this lets the client agent
 * start before the intelligence agent is reachable.
 */
public class A2aCreditAdvisorPort implements CreditAdvisorPort {

    private final String agentUrl;
    private volatile CreditAdvisorAgent agent;

    public A2aCreditAdvisorPort(String agentUrl) {
        this.agentUrl = Objects.requireNonNull(agentUrl);
    }

    @Override
    public String requestAdvice(String ssnDigits) {
        return agent().advise(ssnDigits);
    }

    private CreditAdvisorAgent agent() {
        CreditAdvisorAgent current = agent;
        if (current == null) {
            synchronized (this) {
                if (agent == null) {
                    agent = AgenticServices.a2aBuilder(agentUrl, CreditAdvisorAgent.class).build();
                }
                current = agent;
            }
        }
        return current;
    }
}
