package com.creditagent.web.auth;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.SafeLogging;
import com.creditagent.web.auth.AuthExceptions.InvalidRegistrationException;
import com.creditagent.web.auth.AuthExceptions.SsnAlreadyRegisteredException;
import com.creditagent.web.auth.AuthExceptions.UserAlreadyExistsException;
import com.creditagent.web.kyc.DocumentRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Register, log in, log out and check the current session. Register and login are open; logout and
 * session need the bearer token (see {@link AuthenticationFilter}).
 *
 * <p>Registration is {@code multipart/form-data} with a photo of the user's Social Security card in
 * the {@code ssnCard} part. There is no SSN field: the SSN is what the KYC provider reads from the
 * card, so a user cannot claim someone else's number by typing it.
 */
@RestController
@RequestMapping(path = AuthController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
class AuthController {

    static final String BASE = "/api/v1/auth";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService auth;

    private final java.util.List<SessionEndListener> sessionEndListeners;

    private final org.springframework.beans.factory.ObjectProvider<LoginOffers> loginOffers;
    private final org.springframework.beans.factory.ObjectProvider<LoginAlerts> loginAlerts;

    AuthController(AuthService auth, org.springframework.beans.factory.ObjectProvider<SessionEndListener> sessionEndListeners,
            org.springframework.beans.factory.ObjectProvider<LoginOffers> loginOffers,
            org.springframework.beans.factory.ObjectProvider<LoginAlerts> loginAlerts) {
        this.auth = auth;
        this.loginOffers = loginOffers;
        this.loginAlerts = loginAlerts;
        this.sessionEndListeners = sessionEndListeners.orderedStream().toList();
    }

    /** The registration form. Bound from multipart fields; an {@code ssn} field, if sent, is ignored. */
    record RegisterForm(
            @NotNull @Size(max = 64) String userId,
            @NotNull @Size(max = 256) String password,
            @NotNull @Size(max = 254) String email,
            @NotNull @Size(max = 32) String phone,
            @NotNull @Size(max = 10) String zip,
            @NotNull MultipartFile ssnCard) {
        @Override
        public String toString() {
            return "RegisterForm[userId=" + userId + ", password=<redacted>, email=<redacted>, phone=<redacted>, zip=<redacted>, ssnCard=<redacted>]";
        }
    }

    record RegisterResponse(String userId) {}

    record LoginRequest(@NotNull @Size(max = 64) String userId, @NotNull @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginRequest[userId=" + userId + ", password=<redacted>]";
        }
    }

    /**
     * {@code offers}: card offers matched at login (see {@link LoginOffers}); {@code alerts}: whether the
     * user's email was found on the dark web (see {@link LoginAlerts}). Each is null when there is no source.
     */
    record LoginResponse(String token, String tokenType, String userId, String ssnMasked, long idleTimeoutSeconds,
            Object offers, Object alerts) {}

    record SessionResponse(String userId, String ssnMasked) {}

    @PostMapping(path = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<RegisterResponse> register(@Valid @ModelAttribute RegisterForm form) {
        String userId = auth.register(form.userId(), form.password(), form.email(), form.phone(), form.zip(),
                bytes(form.ssnCard()));
        return ResponseEntity.created(URI.create(BASE + "/session")).body(new RegisterResponse(userId));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request.userId(), request.password());
        LoginOffers offers = loginOffers.getIfAvailable();
        LoginAlerts alerts = loginAlerts.getIfAvailable();
        // Both are optional extras that never throw; asked side by side, so a login waits for the
        // slower of the two, not their sum.
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var offersFuture = tasks.submit(() -> offers == null ? null : offers.offersFor(result.userId()));
            var alertsFuture = tasks.submit(() -> alerts == null ? null : alerts.alertsFor(result.userId()));
            return new LoginResponse(result.token(), "Bearer", result.userId(), result.ssnMasked(),
                    result.idleTimeout().toSeconds(), extra(offersFuture), extra(alertsFuture));
        }
    }

    private static Object extra(java.util.concurrent.Future<Object> future) {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Login extra failed: {}", SafeLogging.describe(e.getCause()));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        auth.logout(user);
        for (SessionEndListener listener : sessionEndListeners) {
            try {
                listener.sessionEnded(user);
            } catch (RuntimeException e) {
                // The session is already gone; whatever the listener holds also expires on its own.
                log.warn("Session end listener failed: {}", SafeLogging.describe(e));
            }
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    SessionResponse session(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return new SessionResponse(user.userId(), user.ssnMasked());
    }

    @ExceptionHandler(InvalidRegistrationException.class)
    ProblemDetail invalid(InvalidRegistrationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    ProblemDetail exists(UserAlreadyExistsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SsnAlreadyRegisteredException.class)
    ProblemDetail ssnTaken(SsnAlreadyRegisteredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 422 with {@code reason}, e.g. {@code IMAGE_BLURRY}, so the UI can ask for a new photo. */
    @ExceptionHandler(DocumentRejectedException.class)
    ProblemDetail documentRejected(DocumentRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setProperty("reason", e.reason());
        return problem;
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ProblemDetail kycUnavailable(ServiceUnavailableException e) {
        log.warn("KYC provider failure: {}", SafeLogging.describe(e));
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not check your Social Security card just now. Please try again in a few minutes.");
    }
}
