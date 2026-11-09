package com.creditagent.connector;

import com.creditagent.common.SafeLogging;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC dispatcher for the connector's MCP endpoint: {@code initialize}, {@code ping},
 * {@code tools/list} and {@code tools/call}. Every request is already authenticated; the user it
 * acts for is passed in, never read from the message.
 *
 * <p>Tool failures the user should hear about come back as a tool result with {@code isError}, as
 * the MCP spec asks, so the model can explain them. Protocol mistakes (unknown method or tool, bad
 * arguments shape) are JSON-RPC errors.
 */
public class ConnectorMcpHandler {

    private static final Logger log = LoggerFactory.getLogger(ConnectorMcpHandler.class);

    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of("2025-11-25", "2025-06-18", "2025-03-26");
    static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";
    static final String SERVER_NAME = "creditagent";
    static final String SERVER_VERSION = "0.1.0";
    static final String INSTRUCTIONS = "Tools for the signed-in CreditAgent user's own credit: their score, full credit"
            + " report, advice for raising the score, and the lock on their report. No tool takes an SSN; the user"
            + " is identified by this connection. Lock or unlock the report only when the user asks to.";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    /** A tool: its definition for {@code tools/list} and what it does. */
    record Tool(ObjectNode definition, BiFunction<ConnectorUser, JsonNode, Object> call) {}

    private final JsonMapper mapper;
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private final Map<String, Tool> tools;
    private final ArrayNode toolList;

    public ConnectorMcpHandler(CreditTools credit, JsonMapper mapper) {
        Objects.requireNonNull(credit);
        this.mapper = Objects.requireNonNull(mapper);
        this.tools = Map.of(
                "get_credit_score", new Tool(
                        definition("get_credit_score", "Credit score",
                                "Returns the user's own credit score (300 to 850) and its band: RED below 600,"
                                        + " ORANGE 600 to 700, GREEN above 700.",
                                noInput(), true),
                        (user, args) -> credit.score(user)),
                "get_credit_report", new Tool(
                        definition("get_credit_report", "Credit report",
                                "Returns the user's own credit report, merged from Equifax, Experian and TransUnion:"
                                        + " accounts with balances, limits and payment history, hard inquiries in the"
                                        + " last 12 months, collections and public records. The SSN in it is masked.",
                                noInput(), true),
                        (user, args) -> credit.report(user)),
                "get_credit_advice", new Tool(
                        definition("get_credit_advice", "Advice to raise the score",
                                "Returns prioritised recommendations for raising the user's credit score, from the"
                                        + " Credit Intelligence Agent, with the score they are based on. Refused while"
                                        + " the user's report is locked. Can take up to a minute.",
                                noInput(), true),
                        (user, args) -> credit.advice(user)),
                "get_report_lock_status", new Tool(
                        definition("get_report_lock_status", "Report lock status",
                                "Says whether the user's credit report is locked. A locked report is refused to"
                                        + " third parties (lenders, agents); the user can still see it.",
                                noInput(), true),
                        (user, args) -> credit.lockStatus(user)),
                "set_report_lock", new Tool(
                        definition("set_report_lock", "Lock or unlock the credit report",
                                "Locks (locked=true) or unlocks (locked=false) the user's credit report against"
                                        + " third-party pulls, and returns the new status. Only call this when the"
                                        + " user explicitly asks to lock or unlock.",
                                lockInput(), false),
                        (user, args) -> credit.setLock(user, lockArgument(args))));
        this.toolList = nodes.arrayNode();
        this.tools.keySet().stream().sorted().forEach(name -> toolList.add(this.tools.get(name).definition()));
    }

    /**
     * Handles one JSON-RPC message for {@code user}.
     *
     * @return the response body, or empty for a notification (the HTTP layer answers 202)
     */
    public Optional<String> handle(String body, ConnectorUser user) {
        JsonNode message;
        try {
            message = mapper.readTree(body);
        } catch (JacksonException e) {
            return Optional.of(error(null, PARSE_ERROR, "Parse error"));
        }
        if (message == null || !message.isObject()) {
            return Optional.of(error(null, INVALID_REQUEST, "Expected a single JSON-RPC object"));
        }
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return Optional.empty(); // notifications/initialized, notifications/cancelled, ...
        }
        if (!id.isString() && !id.isIntegralNumber()) {
            return Optional.of(error(null, INVALID_REQUEST, "id must be a string or an integer"));
        }
        String method = message.path("method").asString("");
        JsonNode params = message.path("params");
        try {
            return Optional.of(switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, nodes.objectNode());
                case "tools/list" -> result(id, nodes.objectNode().set("tools", toolList));
                case "tools/call" -> callTool(id, params, user);
                case "" -> error(id, INVALID_REQUEST, "Missing method");
                default -> error(id, METHOD_NOT_FOUND, "Method not found");
            });
        } catch (RuntimeException e) {
            log.error("MCP request {} failed: {}", method, SafeLogging.describe(e));
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error"));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asString(DEFAULT_PROTOCOL_VERSION);
        ObjectNode result = nodes.objectNode()
                .put("protocolVersion", SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : DEFAULT_PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", SERVER_NAME).put("title", "CreditAgent").put("version", SERVER_VERSION);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private String callTool(JsonNode id, JsonNode params, ConnectorUser user) {
        Tool tool = tools.get(params.path("name").asString(""));
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unknown tool");
        }
        JsonNode arguments = params.path("arguments");
        if (!arguments.isMissingNode() && !arguments.isNull() && !arguments.isObject()) {
            return error(id, INVALID_PARAMS, "arguments must be an object");
        }
        try {
            JsonNode value = mapper.valueToTree(tool.call().apply(user, arguments));
            ObjectNode result = nodes.objectNode();
            result.putArray("content").addObject().put("type", "text").put("text", mapper.writeValueAsString(value));
            result.set("structuredContent", value);
            result.put("isError", false);
            return result(id, result);
        } catch (CreditTools.ToolException | IllegalArgumentException e) {
            ObjectNode result = nodes.objectNode();
            result.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
            result.put("isError", true);
            return result(id, result);
        }
    }

    private static boolean lockArgument(JsonNode arguments) {
        JsonNode locked = arguments.path("locked");
        if (!locked.isBoolean()) {
            throw new IllegalArgumentException("Say whether to lock the report: locked must be true or false.");
        }
        return locked.booleanValue();
    }

    private ObjectNode definition(String name, String title, String description, ObjectNode inputSchema, boolean readOnly) {
        ObjectNode tool = nodes.objectNode().put("name", name).put("title", title).put("description", description);
        tool.set("inputSchema", inputSchema);
        tool.putObject("annotations")
                .put("title", title)
                .put("readOnlyHint", readOnly)
                .put("destructiveHint", false)
                .put("idempotentHint", true)
                .put("openWorldHint", false);
        return tool;
    }

    private ObjectNode noInput() {
        ObjectNode schema = nodes.objectNode().put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode lockInput() {
        ObjectNode schema = nodes.objectNode().put("type", "object");
        schema.putObject("properties").putObject("locked")
                .put("type", "boolean")
                .put("description", "true to lock the report, false to unlock it");
        schema.putArray("required").add("locked");
        schema.put("additionalProperties", false);
        return schema;
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
