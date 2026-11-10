package com.creditagent.web.oauth;

import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AuthenticatedUser;
import com.creditagent.web.auth.ReportStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for the Claude connector (guarded by {@code InternalApiFilter}, so only holders of the
 * service token reach it). Each call carries the user's access token in the body, and the user is
 * whoever that token belongs to: the connector cannot name a user of its own choosing.
 *
 * <ul>
 *   <li>{@code POST /introspect}: the token's user and SSN (RFC 7662 style; {@code active:false}
 *       for any token that is not live)
 *   <li>{@code POST /report-status}: the user's report lock; {@code PUT} sets it
 * </ul>
 */
@RestController
@RequestMapping(path = ConnectorApiController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
public class ConnectorApiController {

    static final String BASE = "/internal/v1/connector";

    private final OAuthService oauth;
    private final AuthService auth;

    ConnectorApiController(OAuthService oauth, AuthService auth) {
        this.oauth = oauth;
        this.auth = auth;
    }

    record TokenRequest(@NotBlank @Size(max = 4096) String token) {
        @Override
        public String toString() {
            return "TokenRequest[token=<redacted>]";
        }
    }

    record StatusUpdate(
            @NotBlank @Size(max = 4096) String token, @NotNull @Pattern(regexp = "LOCKED|UNLOCKED") String reportStatus) {
        @Override
        public String toString() {
            return "StatusUpdate[token=<redacted>, reportStatus=" + reportStatus + "]";
        }
    }

    record Introspection(
            boolean active, String userId, String ssn, String ssnMasked, String resource, String scope, Long expiresAt) {
        static final Introspection INACTIVE = new Introspection(false, null, null, null, null, null, null);

        @Override
        public String toString() {
            return "Introspection[active=" + active + ", userId=" + userId + "]";
        }
    }

    record StatusBody(String reportStatus) {}

    /** Thrown when the token in the body is not live; answered 401. */
    static final class InactiveTokenException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InactiveTokenException() {
            super("The access token is not active.", null, false, false);
        }
    }

    @PostMapping(path = "/introspect", consumes = MediaType.APPLICATION_JSON_VALUE)
    Introspection introspect(@Valid @RequestBody TokenRequest request) {
        if (!oauth.enabled()) {
            return Introspection.INACTIVE;
        }
        return oauth.introspect(request.token())
                .map(i -> new Introspection(true, i.user().userId(), i.ssn().digits(), i.ssn().masked(), i.resource(),
                        i.scope(), i.expiresAt().getEpochSecond()))
                .orElse(Introspection.INACTIVE);
    }

    @PostMapping(path = "/report-status", consumes = MediaType.APPLICATION_JSON_VALUE)
    StatusBody reportStatus(@Valid @RequestBody TokenRequest request) {
        return new StatusBody(auth.reportStatus(user(request.token())).name());
    }

    @PutMapping(path = "/report-status", consumes = MediaType.APPLICATION_JSON_VALUE)
    StatusBody setReportStatus(@Valid @RequestBody StatusUpdate request) {
        AuthenticatedUser user = user(request.token());
        return new StatusBody(auth.setReportStatus(user, ReportStatus.valueOf(request.reportStatus())).name());
    }

    private AuthenticatedUser user(String token) {
        if (!oauth.enabled()) {
            throw new InactiveTokenException();
        }
        return oauth.introspect(token).map(OAuthService.Introspection::user).orElseThrow(InactiveTokenException::new);
    }

    @ExceptionHandler(InactiveTokenException.class)
    ResponseEntity<ProblemDetail> inactive(InactiveTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }
}
