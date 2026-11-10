package com.creditagent.offers;

import com.creditagent.common.SafeLogging;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON-RPC in front of LangChain4j's {@link McpServer}, which answers {@code initialize},
 * {@code tools/list} and {@code tools/call}. This adds what it lacks: notifications (no answer),
 * {@code ping}, and a clean error for anything malformed. Uses Jackson 2, as LangChain4j's MCP types do.
 */
public class OffersMcpHandler {

    private static final Logger log = LoggerFactory.getLogger(OffersMcpHandler.class);

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int INTERNAL_ERROR = -32603;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private final McpServer server;
    private final AtomicLong internalIds = new AtomicLong();

    public OffersMcpHandler(McpServer server) {
        this.server = Objects.requireNonNull(server);
    }

    /** One JSON-RPC message. @return the answer, or empty for a notification (the HTTP layer sends 202) */
    public Optional<String> handle(String body) {
        JsonNode message;
        try {
            message = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return Optional.of(error(null, PARSE_ERROR, "Parse error"));
        }
        if (message == null || !message.isObject()) {
            return Optional.of(error(null, INVALID_REQUEST, "Expected a single JSON-RPC object"));
        }
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return Optional.empty();
        }
        if (!id.isIntegralNumber()) {
            // LangChain4j's McpServer only accepts numeric ids.
            return Optional.of(error(id, INVALID_REQUEST, "Request id must be a number"));
        }
        if ("ping".equals(message.path("method").asText(""))) {
            ObjectNode envelope = nodes.objectNode().put("jsonrpc", "2.0");
            envelope.set("id", id);
            envelope.set("result", nodes.objectNode());
            return Optional.of(envelope.toString());
        }
        try {
            McpJsonRpcMessage response = server.handle(message);
            return Optional.of(response == null ? error(id, INTERNAL_ERROR, "Internal error") : mapper.writeValueAsString(response));
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("MCP request failed: {}", SafeLogging.describe(e));
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error"));
        }
    }

    /**
     * Calls one tool through the MCP server, as an MCP client would; for the REST API.
     *
     * @return the tool result ({@code content}, {@code isError})
     */
    public JsonNode callTool(String tool, ObjectNode arguments) {
        ObjectNode request = nodes.objectNode().put("jsonrpc", "2.0").put("id", internalIds.incrementAndGet())
                .put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", arguments);
        String answer = handle(request.toString()).orElseThrow();
        try {
            JsonNode node = mapper.readTree(answer);
            if (node.has("error")) {
                throw new IllegalStateException("MCP error " + node.path("error").path("code").asInt());
            }
            return node.path("result");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable MCP answer", e);
        }
    }

    ObjectNode arguments() {
        return nodes.objectNode();
    }

    private String error(JsonNode id, int code, String text) {
        ObjectNode envelope = nodes.objectNode().put("jsonrpc", "2.0");
        envelope.set("id", id == null ? nodes.nullNode() : id);
        envelope.putObject("error").put("code", code).put("message", text);
        return envelope.toString();
    }
}
