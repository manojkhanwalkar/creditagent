package com.creditagent.generator;

import com.creditagent.common.AuditLog;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import com.creditagent.reports.CreditReportStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@code POST /internal/v1/reports/erase} with {@code {ssn}}: removes every bureau's stored report for
 * that consumer, for account deletion. Internal only (service token, {@code internalApiTokenFilter}).
 * The SSN travels in the body, never in a URL. Idempotent, so the caller may retry it; 204 whether or
 * not reports existed, so the answer reveals nothing.
 */
@RestController
class ReportEraseController {

    static final String PATH = "/internal/v1/reports/erase";

    private final CreditReportStore store;
    private final AuditLog audit;

    ReportEraseController(CreditReportStore store, AuditLog audit) {
        this.store = store;
        this.audit = audit;
    }

    record EraseRequest(@NotBlank @Size(max = 16) String ssn) {
        @Override
        public String toString() {
            return "EraseRequest[ssn=<redacted>]";
        }
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> erase(@Valid @RequestBody EraseRequest request) {
        Ssn ssn = Ssn.parse(request.ssn());
        store.eraseAll(ssn);
        audit.record(AuditLog.Event.REPORTS_ERASED, "service:internal", "ssnref:" + store.reference(ssn), "OK");
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid SSN.");
    }

    /** The store failed (after the SDK's retries): 503 with Retry-After, which the caller backs off on. */
    @ExceptionHandler({SdkException.class, software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Credit reports could not be erased."));
    }
}
