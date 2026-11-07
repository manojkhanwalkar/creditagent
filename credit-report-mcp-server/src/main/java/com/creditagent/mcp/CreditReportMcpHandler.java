package com.creditagent.mcp;

import com.creditagent.common.SafeLogging;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC dispatcher for the MCP endpoint.
 *
 * <p>LangChain4j's community {@link McpServer} implements {@code initialize}, {@code tools/list} and
 * {@code tools/call} but not resources. The spec asks for the credit files to be an MCP
 * <em>resource</em>, so this class answers {@code initialize} itself (to advertise both
 * capabilities), implements {@code resources/*}, and delegates {@code tools/*} to LangChain4j.
 *
 * <p>Resource URIs have the form {@code credit-report://ssn/{ssn}}. {@code resources/list} is
 * intentionally empty: enumerating credit files would enumerate people.
 */
@Component
public class CreditReportMcpHandler {

    private static final Logger log = LoggerFactory.getLogger(CreditReportMcpHandler.class);

    static final String URI_TEMPLATE = "credit-report://ssn/{ssn}";
    static final String MIME_TYPE = "application/json";
    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of("2025-11-25", "2025-06-18", "2025-03-26");
    static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int RESOURCE_NOT_FOUND = -32002;

    private static final Pattern RESOURCE_URI = Pattern.compile("^credit-report://ssn/([0-9-]{9,11})$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private final McpServer toolServer;
    private final McpImplementation serverInfo;
    private final CreditFileRepository repository;

    public CreditReportMcpHandler(McpServer toolServer, McpImplementation serverInfo, CreditFileRepository repository) {
        this.toolServer = toolServer;
        this.serverInfo = serverInfo;
        this.repository = repository;
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @return the response body, or empty for notifications (the HTTP layer answers 202)
     */
    public Optional<String> handle(String body) {
        JsonNode message;
        try {
            message = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return Optional.of(error(null, PARSE_ERROR, "Parse error"));
        }
        if (message == null || !message.isObject()) {
            // JSON-RPC batching was removed from MCP in 2025-06-18.
            return Optional.of(error(null, INVALID_REQUEST, "Expected a single JSON-RPC object"));
        }

        JsonNode id = message.get("id");
        String method = message.path("method").asText("");
        boolean isNotification = id == null || id.isNull();

        if (isNotification) {
            // notifications/initialized, notifications/cancelled, ... need no answer.
            return Optional.empty();
        }
        if (method.isEmpty()) {
            return Optional.of(error(id, INVALID_REQUEST, "Missing method"));
        }

        try {
            return Optional.of(
                    switch (method) {
                        case "initialize" -> result(id, initialize(message.path("params")));
                        case "ping" -> result(id, nodes.objectNode());
                        case "resources/list" -> result(id, nodes.objectNode().set("resources", nodes.arrayNode()));
                        case "resources/templates/list" -> result(id, resourceTemplates());
                        case "resources/read" -> readResource(id, message.path("params"));
                        case "tools/list", "tools/call" -> delegateToLangChain4j(id, message);
                        default -> error(id, METHOD_NOT_FOUND, "Method not found");
                    });
        } catch (RuntimeException e) {
            log.error("MCP request {} failed: {}", method, SafeLogging.describe(e));
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error"));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText(DEFAULT_PROTOCOL_VERSION);
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : DEFAULT_PROTOCOL_VERSION;

        ObjectNode capabilities = nodes.objectNode();
        capabilities.putObject("tools").put("listChanged", false);
        capabilities.putObject("resources").put("subscribe", false).put("listChanged", false);

        ObjectNode info = nodes.objectNode().put("name", serverInfo.getName()).put("version", serverInfo.getVersion());

        ObjectNode result = nodes.objectNode().put("protocolVersion", version);
        result.set("capabilities", capabilities);
        result.set("serverInfo", info);
        result.put("instructions", "Read credit reports with resources/read on " + URI_TEMPLATE + ".");
        return result;
    }

    private ObjectNode resourceTemplates() {
        ObjectNode template = nodes.objectNode()
                .put("uriTemplate", URI_TEMPLATE)
                .put("name", "credit-report")
                .put("title", "Credit report")
                .put("description", "Credit report for a US Social Security Number, read from the credit file store.")
                .put("mimeType", MIME_TYPE);
        ArrayNode templates = nodes.arrayNode().add(template);
        return nodes.objectNode().set("resourceTemplates", templates);
    }

    private String readResource(JsonNode id, JsonNode params) {
        String uri = params.path("uri").asText("");
        Matcher matcher = RESOURCE_URI.matcher(uri);
        if (!matcher.matches()) {
            return error(id, INVALID_PARAMS, "Unknown resource URI; expected " + URI_TEMPLATE);
        }
        Ssn ssn;
        try {
            ssn = Ssn.parse(matcher.group(1));
        } catch (InvalidSsnException e) {
            return error(id, INVALID_PARAMS, e.getMessage());
        }
        Optional<String> report = repository.findReportJson(ssn);
        if (report.isEmpty()) {
            // Do not echo the URI: it contains the SSN.
            return error(id, RESOURCE_NOT_FOUND, "Resource not found");
        }
        ObjectNode content = nodes.objectNode().put("uri", uri).put("mimeType", MIME_TYPE).put("text", report.get());
        return result(id, nodes.objectNode().set("contents", nodes.arrayNode().add(content)));
    }

    private String delegateToLangChain4j(JsonNode id, JsonNode message) {
        if (!id.isIntegralNumber()) {
            // LangChain4j's McpServer only accepts numeric ids.
            return error(id, INVALID_REQUEST, "Request id must be a number for tool calls");
        }
        McpJsonRpcMessage response = toolServer.handle(message);
        if (response == null) {
            return error(id, INTERNAL_ERROR, "Internal error");
        }
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise tool response", e);
        }
    }

    private String result(JsonNode id, JsonNode result) {
        ObjectNode envelope = nodes.objectNode().put("jsonrpc", "2.0");
        envelope.set("id", id);
        envelope.set("result", result);
        return envelope.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode envelope = nodes.objectNode().put("jsonrpc", "2.0");
        envelope.set("id", id == null ? nodes.nullNode() : id);
        envelope.putObject("error").put("code", code).put("message", message);
        return envelope.toString();
    }
}
