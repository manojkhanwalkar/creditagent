package com.creditagent.agent.a2a;

import java.util.List;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;

/** The public A2A agent card for the Credit Intelligence Agent. */
public final class AgentCards {

    public static final String NAME = "Credit Intelligence Agent";

    private AgentCards() {}

    public static AgentCard creditIntelligence(String publicUrl, String version) {
        return AgentCard.builder()
                .name(NAME)
                .description("Reads a consumer's credit report, scores it and recommends how to raise the score.")
                .version(version)
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), publicUrl)))
                // Blocking request/response only: keeps the A2A surface small.
                .capabilities(AgentCapabilities.builder().streaming(false).pushNotifications(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("credit-score-recommendations")
                        .name("Credit score recommendations")
                        .description("Given an SSN, returns the score and prioritised actions to improve it, as JSON.")
                        .tags(List.of("credit", "score", "recommendations"))
                        .examples(List.of("123-45-6789"))
                        .build()))
                .build();
    }
}
