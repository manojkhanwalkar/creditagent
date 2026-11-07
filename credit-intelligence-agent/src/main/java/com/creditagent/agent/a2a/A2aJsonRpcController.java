package com.creditagent.agent.a2a;

import com.creditagent.common.SafeLogging;
import static org.a2aproject.sdk.server.ServerCallContext.TRANSPORT_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.HEADERS_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.METHOD_NAME_KEY;

import com.google.gson.JsonParseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A JSON-RPC transport for Spring MVC, modelled on the a2a-java Quarkus reference server.
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json}: the public agent card
 *   <li>{@code POST /}: JSON-RPC ({@code SendMessage}, {@code GetTask}); streaming methods are refused
 *       because the card advertises {@code streaming=false}
 * </ul>
 */
@RestController
class A2aJsonRpcController {

    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);
    private static final Set<String> FORWARDED_HEADERS = Set.of("a2a-version", "a2a-extensions", "content-type");

    private final Supplier<JSONRPCHandler> handler;

    A2aJsonRpcController(Supplier<JSONRPCHandler> handler) {
        this.handler = handler;
    }

    @GetMapping(path = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> agentCard() throws JsonProcessingException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtil.toJson(handler.get().getAgentCard()));
    }

    @PostMapping(path = "/", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> jsonRpc(@RequestBody String body, HttpServletRequest http) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(dispatch(body, http));
    }

    private String dispatch(String body, HttpServletRequest http) {
        try {
            A2ARequest<?> request = JSONRPCUtils.parseRequestBody(body, null);
            ServerCallContext context = callContext(http, request.getMethod());
            if (request instanceof SendMessageRequest send) {
                SendMessageResponse response = handler.get().onMessageSend(send, context);
                if (response.getError() != null) {
                    return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
                }
                return JSONRPCUtils.toJsonRPCResultResponse(
                        response.getId(), ProtoUtils.ToProto.taskOrMessage(response.getResult()));
            }
            if (request instanceof GetTaskRequest get) {
                GetTaskResponse response = handler.get().onGetTask(get, context);
                if (response.getError() != null) {
                    return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
                }
                return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), ProtoUtils.ToProto.task(response.getResult()));
            }
            return JSONRPCUtils.toJsonRPCErrorResponse(request.getId(), new UnsupportedOperationError());
        } catch (A2AError e) {
            return JSONRPCUtils.toJsonRPCErrorResponse(null, e);
        } catch (InvalidParamsJsonMappingException e) {
            return JSONRPCUtils.toJsonRPCErrorResponse(e.getId(), new InvalidParamsError(null, "Invalid params", null));
        } catch (MethodNotFoundJsonMappingException e) {
            return JSONRPCUtils.toJsonRPCErrorResponse(e.getId(), new MethodNotFoundError(null, "Method not found", null));
        } catch (IdJsonMappingException e) {
            return JSONRPCUtils.toJsonRPCErrorResponse(e.getId(), new InvalidRequestError(null, "Invalid id", null));
        } catch (JsonMappingException e) {
            return JSONRPCUtils.toJsonRPCErrorResponse(null, new InvalidRequestError(null, "Invalid request", null));
        } catch (JsonProcessingException | JsonParseException e) {
            return JSONRPCUtils.toJsonRPCErrorResponse(null, new JSONParseError("Parse error"));
        } catch (RuntimeException e) {
            log.error("A2A request failed: {}", SafeLogging.describe(e));
            return JSONRPCUtils.toJsonRPCErrorResponse(null, new InternalError("Internal error"));
        }
    }

    private static ServerCallContext callContext(HttpServletRequest http, String method) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(http.getHeaderNames())) {
            // Only protocol headers are passed on; never cookies or authorization headers.
            if (FORWARDED_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, http.getHeader(name));
            }
        }
        Map<String, Object> state = new HashMap<>();
        state.put(HEADERS_KEY, headers);
        state.put(METHOD_NAME_KEY, method);
        state.put(TRANSPORT_KEY, TransportProtocol.JSONRPC);
        // A2AExtensions parsing is skipped: the agent declares no extensions.
        return new ServerCallContext(
                UnauthenticatedUser.INSTANCE, state, Set.of(), http.getHeader(A2AHeaders.A2A_VERSION));
    }
}
