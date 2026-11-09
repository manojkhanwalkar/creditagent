package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's credit report lock: {@code GET} reads it, {@code PUT
 * {"reportStatus":"LOCKED"|"UNLOCKED"}} sets it. Needs the bearer token (see {@link
 * AuthenticationFilter}); a user can only change their own report's status.
 */
@RestController
@RequestMapping(path = ReportStatusController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class ReportStatusController {

    static final String PATH = "/api/v1/credit-report/status";

    private final AuthService auth;

    ReportStatusController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * The status as a string, matched exactly: binding straight to the enum would also accept its
     * ordinals ({@code 0}, {@code "1"}), which would silently change meaning if the constants moved.
     */
    record ReportStatusBody(@NotNull @Pattern(regexp = "LOCKED|UNLOCKED") String reportStatus) {

        static ReportStatusBody of(ReportStatus status) {
            return new ReportStatusBody(status.name());
        }
    }

    @GetMapping
    ReportStatusBody status(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ReportStatusBody.of(auth.reportStatus(user));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ReportStatusBody update(
            @Valid @RequestBody ReportStatusBody request,
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ReportStatusBody.of(auth.setReportStatus(user, ReportStatus.valueOf(request.reportStatus())));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }
}
