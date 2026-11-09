package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The 401 answer for the auth controllers: a problem detail with {@code WWW-Authenticate: Bearer},
 * the same as {@link AuthenticationFilter} sends for a missing or invalid token.
 */
@RestControllerAdvice(assignableTypes = {AuthController.class, ReportStatusController.class})
class AuthErrorHandling {

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }
}
