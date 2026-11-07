package com.creditagent.mcp;

import java.util.Optional;
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
 * MCP Streamable HTTP transport, single-response mode: every POST is answered with one
 * {@code application/json} body (or 202 for notifications). No server-initiated SSE stream is
 * offered, so GET and DELETE answer 405 as the transport spec allows.
 */
@RestController
class McpHttpController {

    static final long MAX_BODY_BYTES = 64 * 1024;

    private final CreditReportMcpHandler handler;
    private final McpServerProperties properties;

    McpHttpController(CreditReportMcpHandler handler, McpServerProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> post(
            @RequestBody String body, @RequestHeader(name = "Origin", required = false) String origin) {
        if (origin != null && !properties.allowedOrigins().contains(origin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body.length() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        Optional<String> response = handler.handle(body);
        return response.map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    @GetMapping("/mcp")
    ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping("/mcp")
    ResponseEntity<Void> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
