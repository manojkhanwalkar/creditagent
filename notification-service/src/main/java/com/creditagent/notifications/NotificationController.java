package com.creditagent.notifications;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@code POST /internal/v1/notifications}: sends one templated message, once per {@code Idempotency-Key}.
 * Internal only (service token). The contract is {@code static/openapi/notification-service-v1.yaml}.
 * No answer echoes the destination or a param value.
 */
@RestController
class NotificationController {

    static final String PATH = "/internal/v1/notifications";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    /** The body. {@link #toString} hides the destination and the param values (a sign-in code). */
    record SendRequest(Channel channel, String to, Template template, Map<String, String> params) {
        @Override
        public String toString() {
            return "SendRequest[channel=" + channel + ", template=" + template + ", to=<redacted>, params="
                    + (params == null ? "[]" : new java.util.TreeSet<>(params.keySet())) + "]";
        }
    }

    record SendResponse(String id, String status) {}

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SendResponse> send(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody SendRequest request) {
        NotificationService.Accepted accepted = service.send(key, request.channel(), request.to(), request.template(),
                request.params());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SendResponse(accepted.id(), "SENT"));
    }

    @ExceptionHandler(InvalidNotificationException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidNotificationException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(NotificationService.KeyReusedException.class)
    ResponseEntity<ProblemDetail> conflict(NotificationService.KeyReusedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(NotificationService.InProgressException.class)
    ResponseEntity<ProblemDetail> inProgress(NotificationService.InProgressException e) {
        return unavailable(e.retryAfterSeconds(), e.getMessage());
    }

    /** 422 when the provider clearly refused (a retry with the same key may send); else the outcome is unknown: 503. */
    @ExceptionHandler(DeliveryException.class)
    ResponseEntity<ProblemDetail> delivery(DeliveryException e) {
        if (e.refused()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The provider refused the message.");
            problem.setProperty("refused", true);
            return ResponseEntity.unprocessableContent().body(problem);
        }
        return unavailable(1, "The provider did not confirm the message; it may still arrive.");
    }

    /** The table failed (after the SDK's own retries): nothing was sent. */
    @ExceptionHandler(SdkException.class)
    ResponseEntity<ProblemDetail> storeUnavailable(SdkException e) {
        return unavailable(1, "The notification could not be recorded; nothing was sent.");
    }

    private static ResponseEntity<ProblemDetail> unavailable(long retryAfterSeconds, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail));
    }
}
