package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.InvalidRegistrationException;
import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.creditagent.web.auth.AuthExceptions.UserAlreadyExistsException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register, log in, log out and check the current session. Register and login are open; logout and
 * session need the bearer token (see {@link AuthenticationFilter}).
 */
@RestController
@RequestMapping(path = AuthController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
class AuthController {

    static final String BASE = "/api/v1/auth";

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    record RegisterRequest(
            @NotNull @Size(max = 64) String userId,
            @NotNull @Size(max = 256) String password,
            @NotNull @Size(max = 254) String email,
            @NotNull @Size(max = 32) String phone) {
        @Override
        public String toString() {
            return "RegisterRequest[userId=" + userId + ", password=<redacted>, email=<redacted>, phone=<redacted>]";
        }
    }

    record RegisterResponse(String userId) {}

    record LoginRequest(@NotNull @Size(max = 64) String userId, @NotNull @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginRequest[userId=" + userId + ", password=<redacted>]";
        }
    }

    record LoginResponse(String token, String tokenType, String userId, long idleTimeoutSeconds) {}

    record SessionResponse(String userId) {}

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        String userId = auth.register(request.userId(), request.password(), request.email(), request.phone());
        return ResponseEntity.created(URI.create(BASE + "/session")).body(new RegisterResponse(userId));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request.userId(), request.password());
        return new LoginResponse(result.token(), "Bearer", result.userId(), result.idleTimeout().toSeconds());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        auth.logout(user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    SessionResponse session(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return new SessionResponse(user.userId());
    }

    @ExceptionHandler(InvalidRegistrationException.class)
    ProblemDetail invalid(InvalidRegistrationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    ProblemDetail exists(UserAlreadyExistsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }
}
