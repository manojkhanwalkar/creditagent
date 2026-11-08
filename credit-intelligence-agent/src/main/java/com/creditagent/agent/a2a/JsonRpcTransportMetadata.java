package com.creditagent.agent.a2a;

import org.a2aproject.sdk.server.TransportMetadata;
import org.a2aproject.sdk.spec.TransportProtocol;

/**
 * Tells the SDK's {@code AgentCardValidator} that this server offers the JSON-RPC transport
 * (served by {@link A2aJsonRpcController}). The SDK registers this only from its Quarkus reference
 * servers, which this Spring Boot agent does not use, so without it the validator rejects the
 * agent card. Registered in {@code META-INF/services/org.a2aproject.sdk.server.TransportMetadata}.
 */
public final class JsonRpcTransportMetadata implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
