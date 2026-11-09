package com.creditagent.web.auth;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for third-party consumers of credit reports (the Credit Intelligence Agent, the MCP
 * server's tool): is this SSN's report locked by its owner? Guarded by {@code InternalApiFilter}.
 *
 * <p>The answer is {@code false} both for an unlocked report and for an SSN with no account, so it
 * does not reveal who has registered. The SSN travels in the body, never in the URL, and is never
 * logged.
 */
@RestController
class ReportLockLookupController {

    static final String PATH = "/internal/v1/report-locks/lookup";

    private final AuthService auth;

    ReportLockLookupController(AuthService auth) {
        this.auth = auth;
    }

    record LookupRequest(@NotBlank @Size(max = 20) String ssn) {
        @Override
        public String toString() {
            return "LookupRequest[ssn=<redacted>]";
        }
    }

    record LookupResponse(boolean locked) {}

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    LookupResponse lookup(@Valid @RequestBody LookupRequest request) {
        return new LookupResponse(auth.isReportLocked(Ssn.parse(request.ssn())));
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
