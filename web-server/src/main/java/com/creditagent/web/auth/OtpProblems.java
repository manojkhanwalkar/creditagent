package com.creditagent.web.auth;

import com.creditagent.common.SafeLogging;
import com.creditagent.web.auth.AuthExceptions.InvalidOtpRequestException;
import com.creditagent.web.auth.AuthExceptions.OtpFailedException;
import com.creditagent.web.auth.AuthExceptions.TooManyCodesException;
import com.creditagent.web.auth.otp.OtpDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The HTTP answers for the one-time-code step, shared by the login API and the connector's consent
 * form so both answer the same: 400 for a malformed request, 401 with {@code restart} and
 * {@code attemptsLeft}, 429 with {@code Retry-After}, 503 when the code could not be sent.
 */
public final class OtpProblems {

    private static final Logger log = LoggerFactory.getLogger(OtpProblems.class);

    static final String LAST_SEND_UNCONFIRMED = "We could not confirm that your code was sent, and this sign-in"
            + " cannot send another. If it does not arrive in a few minutes, please log in again.";

    static final String NOT_SENT = "We could not send your code just now. Please try again, or choose the other option.";

    private OtpProblems() {}

    public static ResponseEntity<ProblemDetail> invalid(InvalidOtpRequestException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    public static ResponseEntity<ProblemDetail> failed(OtpFailedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setProperty("restart", e.restart());
        if (e.attemptsLeft() != null) {
            problem.setProperty("attemptsLeft", e.attemptsLeft());
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").body(problem);
    }

    public static ResponseEntity<ProblemDetail> tooMany(TooManyCodesException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setProperty("restart", e.restart());
        long seconds = Math.max(1, e.retryAfter().toSeconds());
        problem.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)).body(problem);
    }

    /**
     * 503. When the provider refused the message, the user can retry or switch channel at once. When it
     * may still arrive, the send counted: {@code retryAfterSeconds} and {@code Retry-After} say when a new
     * code may be asked for.
     */
    public static ResponseEntity<ProblemDetail> notSent(OtpDeliveryException e) {
        log.warn("Sign-in code not sent: {}", SafeLogging.describe(e));
        if (!e.refused() && e.lastSend()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, LAST_SEND_UNCONFIRMED));
        }
        if (e.refused() || e.retryAfter() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, NOT_SENT));
        }
        long seconds = Math.max(1, e.retryAfter().toSeconds());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not confirm that your code was sent. If it does not arrive, ask for a new one in "
                        + seconds + " seconds, or choose the other option then.");
        problem.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)).body(problem);
    }
}
