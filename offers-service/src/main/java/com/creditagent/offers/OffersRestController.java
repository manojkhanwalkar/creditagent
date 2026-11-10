package com.creditagent.offers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two interfaces:
 *
 * <ul>
 *   <li>{@code POST /mcp}: MCP over Streamable HTTP (single-response mode), tool
 *       {@value OfferTools#TOOL};
 *   <li>{@code POST /api/v1/offers} with {@code {score, zip}}: the REST API. It plugs into the MCP
 *       interface: it calls the same tool through the same MCP server, so the two cannot drift apart.
 * </ul>
 *
 * Neither takes anything that identifies a person: a score and a ZIP code only.
 */
@RestController
class OffersRestController {

    static final String MCP_PATH = "/mcp";
    static final String OFFERS_PATH = "/api/v1/offers";
    static final long MAX_BODY_BYTES = 16 * 1024;

    private final OffersMcpHandler mcp;

    OffersRestController(OffersMcpHandler mcp) {
        this.mcp = mcp;
    }

    record OffersRequest(
            @NotNull @Min(300) @Max(850) Integer score,
            @Pattern(regexp = "^\\d{5}(-\\d{4})?$", message = "must be a 5-digit US ZIP code") String zip) {}

    @PostMapping(path = OFFERS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> offers(@Valid @RequestBody OffersRequest request) {
        ObjectNode arguments = mcp.arguments().put("score", request.score());
        if (request.zip() != null) {
            arguments.put("zip", request.zip());
        }
        JsonNode result = mcp.callTool(OfferTools.TOOL, arguments);
        String text = result.path("content").path(0).path("text").asText("");
        if (result.path("isError").asBoolean(false) || text.isEmpty()) {
            // Input was validated above, so a tool error here is the server's fault.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"Offers could not be generated.\"}");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(text);
    }

    @PostMapping(path = MCP_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> mcp(@RequestBody String body,
            @RequestHeader(name = "Origin", required = false) String origin) {
        if (origin != null) {
            // Browsers have no business here; refusing every Origin closes DNS-rebinding tricks.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<String> answer = mcp.handle(body);
        return answer.map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    /** No server-initiated stream and no sessions, so GET and DELETE answer 405 (the spec allows it). */
    @GetMapping(MCP_PATH)
    ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping(MCP_PATH)
    ResponseEntity<Void> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    ProblemDetail broken(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Offers could not be generated.");
    }
}
