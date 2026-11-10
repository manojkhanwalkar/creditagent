package com.creditagent.advisor;

import com.creditagent.common.CreditReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for the web server (guarded by {@link com.creditagent.web.support.ServiceTokenFilter}). A conversation id is an
 * opaque value the web server derives from the user's session; the agent never sees who the user is.
 *
 * <ul>
 *   <li>{@code POST /internal/v1/conversations/{id}/messages}: {@code {message, report}} in,
 *       {@code {reply, disclaimer, refused}} out
 *   <li>{@code DELETE /internal/v1/conversations/{id}}: forget the conversation (logout)
 * </ul>
 */
@RestController
@RequestMapping(path = AdvisorController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
class AdvisorController {

    static final String BASE = "/internal/v1/conversations";
    static final String ID_PATTERN = "[A-Za-z0-9_-]{16,128}";

    private final AdvisorService advisor;

    AdvisorController(AdvisorService advisor) {
        this.advisor = advisor;
    }

    /** The size limit is generous here: the web server and the guardrails enforce the real one. */
    record ChatRequest(@NotNull @Size(max = 8000) String message, @NotNull @Valid CreditReport report) {
        @Override
        public String toString() {
            return "ChatRequest[message=<redacted>, report=<redacted>]";
        }
    }

    @PostMapping(path = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    AdvisorService.Reply chat(@PathVariable @Pattern(regexp = ID_PATTERN) String id, @Valid @RequestBody ChatRequest request) {
        return advisor.chat(id, request.message(), request.report());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> forget(@PathVariable @Pattern(regexp = ID_PATTERN) String id) {
        advisor.forget(id);
        return ResponseEntity.noContent().build();
    }
}
