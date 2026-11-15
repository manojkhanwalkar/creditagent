package com.creditagent.web.account;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.SafeLogging;
import com.creditagent.common.Ssn;
import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthenticatedUser;
import com.creditagent.web.auth.NotOwnSsnException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's data rights. Both need the bearer token and the user's own SSN (only its keyed
 * hash is stored); deletion also needs the password again.
 *
 * <ul>
 *   <li>{@code POST /api/v1/account/export} {@code {ssn}}: a JSON download of everything held about them.
 *   <li>{@code POST /api/v1/account/delete} {@code {password, ssn}}: deletes the account and its data; 204.
 * </ul>
 *
 * POST, not GET or DELETE, so the SSN and password travel in a body, never in a URL or a log line.
 */
@RestController
@RequestMapping(path = AccountController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
class AccountController {

    static final String BASE = "/api/v1/account";

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    record ExportRequest(@NotBlank @Size(max = 20) String ssn) {
        @Override
        public String toString() {
            return "ExportRequest[ssn=<redacted>]";
        }
    }

    record DeleteRequest(@NotBlank @Size(max = 256) String password, @NotBlank @Size(max = 20) String ssn) {
        @Override
        public String toString() {
            return "DeleteRequest[password=<redacted>, ssn=<redacted>]";
        }
    }

    @PostMapping(path = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountService.Export> export(@Valid @RequestBody ExportRequest request,
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        AccountService.Export export = accounts.export(user, Ssn.parse(request.ssn()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("creditagent-data.json").build().toString())
                .body(export);
    }

    @PostMapping(path = "/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> delete(@Valid @RequestBody DeleteRequest request,
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        accounts.delete(user, request.password(), Ssn.parse(request.ssn()));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotOwnSsnException.class)
    ProblemDetail notOwnSsn(NotOwnSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(AccountService.NotTheOwnerException.class)
    ProblemDetail notTheOwner(AccountService.NotTheOwnerException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    static final String TRY_AGAIN = "We could not finish this just now. Your account is still open; please try again in a few minutes.";

    /**
     * An erase or read elsewhere, or the users table, failed: the account is kept (some of its data may
     * already be erased), and every step is idempotent, so the user can try again.
     */
    @ExceptionHandler({ServiceUnavailableException.class, software.amazon.awssdk.core.exception.SdkException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException e) {
        log.warn("Account request failed downstream: {}", SafeLogging.describe(e));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, TRY_AGAIN));
    }
}
