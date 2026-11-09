package com.creditagent.connector;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.SafeLogging;
import com.creditagent.web.support.KeyedRateLimiter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connector's HTTP face: the MCP Streamable HTTP endpoint ({@code POST /mcp}, single-response
 * mode, as in the Credit Report MCP server) and its OAuth protected resource metadata (RFC 9728).
 *
 * <p>Every MCP request needs {@code Authorization: Bearer <access token>} issued by the web server
 * for this connector. Without one the answer is 401 with a {@code WWW-Authenticate} challenge that
 * points at the metadata, which is how Claude discovers where to sign the user in.
 */
@RestController
class McpHttpController {

    static final String MCP_PATH = "/mcp";
    static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";
    static final String SCOPE = "credit";
    static final long MAX_BODY_BYTES = 64 * 1024;
    static final int MAX_TOKEN_LENGTH = 4096;
    /** A compact JWE: five base64url parts. Anything else is refused without asking the web server. */
    static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]*){4}$");
    private static final String BEARER = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(McpHttpController.class);

    private final ConnectorMcpHandler handler;
    private final AccountClient accounts;
    private final ConnectorProperties properties;
    private final KeyedRateLimiter perUser;

    McpHttpController(ConnectorMcpHandler handler, AccountClient accounts, ConnectorProperties properties,
            KeyedRateLimiter perUser) {
        this.handler = handler;
        this.accounts = accounts;
        this.properties = properties;
        this.perUser = perUser;
    }

    /** RFC 9728, at the root and at the path-suffixed location clients try first for {@code /mcp}. */
    @GetMapping(path = {RESOURCE_METADATA_PATH, RESOURCE_METADATA_PATH + MCP_PATH}, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> resourceMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", properties.resource());
        metadata.put("authorization_servers", List.of(properties.authorizationServer()));
        metadata.put("scopes_supported", List.of(SCOPE));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", "CreditAgent");
        return metadata;
    }

    @PostMapping(path = MCP_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> post(
            @RequestBody String body,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (origin != null && !properties.allowedOrigins().contains(origin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body.length() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        String token = bearerToken(authorization);
        if (token == null) {
            return challenge(null);
        }
        if (token.length() > MAX_TOKEN_LENGTH || !TOKEN_SHAPE.matcher(token).matches()) {
            return challenge("invalid_token");
        }
        Optional<ConnectorUser> user;
        try {
            user = accounts.authenticate(token);
        } catch (ServiceUnavailableException e) {
            log.warn("Token introspection failed: {}", SafeLogging.describe(e));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").build();
        }
        // The token must have been issued for this connector (RFC 8707): one for another resource
        // is someone else's, even if the web server issued it.
        if (user.isEmpty() || !properties.resource().equals(user.get().resource())) {
            return challenge("invalid_token");
        }
        if (!user.get().hasScope(SCOPE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate("insufficient_scope")).build();
        }
        long wait = perUser.acquire(user.get().userId());
        if (wait > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, Long.toString(wait)).build();
        }
        return handler.handle(body, user.get())
                .map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    /** No server-initiated SSE stream and no sessions, so GET and DELETE answer 405 (the spec allows it). */
    @GetMapping(MCP_PATH)
    ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping(MCP_PATH)
    ResponseEntity<Void> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private ResponseEntity<String> challenge(String error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(error)).build();
    }

    private String wwwAuthenticate(String error) {
        StringBuilder value = new StringBuilder("Bearer resource_metadata=\"").append(properties.resourceMetadataUrl())
                .append("\", scope=\"").append(SCOPE).append('"');
        if (error != null) {
            value.append(", error=\"").append(error).append('"');
        }
        return value.toString();
    }
}
