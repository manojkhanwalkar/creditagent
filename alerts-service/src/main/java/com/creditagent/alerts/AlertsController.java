package com.creditagent.alerts;

import jakarta.validation.Valid;
import com.creditagent.common.EmailAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
 * {@code POST /internal/v1/alerts/lookup} with {@code {email}}: whether the dark web monitor has found
 * this address, and where and when. Internal only (service token, see {@code ServiceTokenFilter}). The
 * address travels in the body, never in a URL, and is not echoed back.
 */
@RestController
class AlertsController {

    static final String PATH = "/internal/v1/alerts/lookup";
    static final String ERASE_PATH = "/internal/v1/alerts/erase";

    private final AlertRepository alerts;
    private final com.creditagent.common.AuditLog audit;

    AlertsController(AlertRepository alerts, com.creditagent.common.AuditLog audit) {
        this.alerts = alerts;
        this.audit = audit;
    }

    /** The same email rule as registration, so every registered address can be looked up. */
    record LookupRequest(@NotBlank @Size(max = EmailAddress.MAX_LENGTH) @Pattern(regexp = EmailAddress.REGEX) String email) {
        @Override
        public String toString() {
            return "LookupRequest[email=<redacted>]";
        }
    }

    record Finding(String location, Instant foundAt) {}

    record LookupResponse(boolean exposed, List<Finding> findings) {}

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    LookupResponse lookup(@Valid @RequestBody LookupRequest request) {
        List<Finding> findings = alerts.find(request.email()).stream()
                .map(alert -> new Finding(alert.location(), alert.timestamp()))
                .toList();
        return new LookupResponse(!findings.isEmpty(), findings);
    }

    /**
     * {@code POST /internal/v1/alerts/erase} with {@code {email}}: deletes every alert for the address
     * (account deletion). Idempotent; 204 either way, so the answer reveals nothing. The audit record
     * names the address by its SHA-256 prefix (the users table's email-claim key), not the address.
     */
    @PostMapping(path = ERASE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> erase(@Valid @RequestBody LookupRequest request) {
        int deleted = alerts.eraseAll(request.email());
        audit.record(com.creditagent.common.AuditLog.Event.ALERTS_ERASED, "service:internal",
                "emailref:" + emailReference(request.email()), "OK", java.util.Map.of("alerts", deleted));
        return ResponseEntity.noContent().build();
    }

    static String emailReference(String email) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Alert.normalise(email).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** An erase that could not delete everything: 503 with Retry-After, so the caller retries the whole erase. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> incomplete(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Alerts could not be erased."));
    }

    /** DynamoDB failed (after the SDK's own retries): 503 with Retry-After, which callers back off on. */
    @ExceptionHandler(SdkException.class)
    ResponseEntity<ProblemDetail> unavailable(SdkException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Alerts could not be read."));
    }
}
