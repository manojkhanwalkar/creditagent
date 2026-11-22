package com.creditagent.web.auth;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.SafeLogging;
import com.creditagent.web.auth.AuthExceptions.InvalidOtpRequestException;
import com.creditagent.web.auth.AuthExceptions.InvalidRegistrationException;
import com.creditagent.web.auth.AuthExceptions.OtpFailedException;
import com.creditagent.web.auth.AuthExceptions.TooManyCodesException;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpDeliveryException;
import com.creditagent.web.auth.AuthExceptions.EmailAlreadyRegisteredException;
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
 * Register, log in, log out and check the current session. Register and the three login steps are
 * open; logout and session need the bearer token (see {@link AuthenticationFilter}).
 *
 * <p>Login takes two factors (see {@link OtpLoginService}): {@code POST /login} checks the password and
 * answers with a challenge and the masked places a code can go; {@code POST /login/otp} sends a code to
 * the chosen one; {@code POST /login/verify} checks the code and only then issues the token.
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
    private final OtpLoginService otp;

    private final java.util.List<SessionEndListener> sessionEndListeners;

    private final org.springframework.beans.factory.ObjectProvider<LoginOffers> loginOffers;
    private final org.springframework.beans.factory.ObjectProvider<LoginAlerts> loginAlerts;
    private final org.springframework.beans.factory.ObjectProvider<AccountSubscriptions> subscriptions;

    AuthController(AuthService auth, OtpLoginService otp, org.springframework.beans.factory.ObjectProvider<SessionEndListener> sessionEndListeners,
            org.springframework.beans.factory.ObjectProvider<LoginOffers> loginOffers,
            org.springframework.beans.factory.ObjectProvider<LoginAlerts> loginAlerts,
            org.springframework.beans.factory.ObjectProvider<AccountSubscriptions> subscriptions) {
        this.auth = auth;
        this.otp = otp;
        this.loginOffers = loginOffers;
        this.loginAlerts = loginAlerts;
        this.subscriptions = subscriptions;
        this.sessionEndListeners = sessionEndListeners.orderedStream().toList();
    }

    /**
     * The registration form. Bound from multipart fields; an {@code ssn} field, if sent, is ignored (the
     * SSN comes from the card). The email and mobile number are both required: sign-in codes go to one
     * of them at every login.
     *
     * @param authorize the user ticked "I agree to the terms and privacy notice and authorise CreditAgent
     *     to obtain my credit reports" (FCRA written instruction); must be {@code true}
     * @param idProtection the user also wants to buy ID Protection; optional, and registration succeeds
     *     without it or if the payment cannot be taken
     */
    record RegisterForm(
            @NotNull @Size(max = 64) String userId,
            @NotNull @Size(max = 256) String password,
            @NotNull @Size(max = 254) String email,
            @NotNull @Size(max = 32) String mobilePhone,
            @NotNull @Size(max = 10) String zip,
            Boolean authorize,
            @NotNull MultipartFile ssnCard,
            Boolean idProtection) {
        @Override
        public String toString() {
            return "RegisterForm[userId=" + userId + ", password=<redacted>, email=<redacted>, mobilePhone=<redacted>, zip=<redacted>, authorize="
                    + authorize + ", ssnCard=<redacted>, idProtection=" + idProtection + "]";
        }
    }

    static final String AUTHORIZATION_REQUIRED = "To open an account, agree to the terms of use and privacy notice and"
            + " authorise CreditAgent to obtain your credit reports.";

    /** {@code idProtection}: the outcome of the ID Protection the user asked for; null when they did not. */
    record RegisterResponse(String userId, Object idProtection) {}

    record LoginRequest(@NotNull @Size(max = 64) String userId, @NotNull @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginRequest[userId=" + userId + ", password=<redacted>]";
        }
    }

    /** The password step's answer: no token yet, only where a code can be sent. */
    record LoginChallengeResponse(String challengeId, java.util.List<OtpLoginService.ChannelOption> channels,
            long expiresInSeconds) {}

    record SendCodeRequest(@NotNull @Size(max = 64) String challengeId, @NotNull @Size(max = 16) String channel) {}

    record CodeSentResponse(OtpChannel channel, String destination, long codeExpiresInSeconds, long resendAfterSeconds) {}

    record VerifyCodeRequest(@NotNull @Size(max = 64) String challengeId, @NotNull @Size(max = 16) String code) {
        @Override
        public String toString() {
            return "VerifyCodeRequest[challengeId=<redacted>, code=<redacted>]";
        }
    }

    /**
     * {@code offers}: card offers matched at login (see {@link LoginOffers}); {@code alerts}: whether the
     * user's email was found on the dark web (see {@link LoginAlerts}); {@code subscriptions}: the user's
     * paid subscriptions and what to offer them (see {@link AccountSubscriptions}). Each is null when there
     * is no source.
     */
    record LoginResponse(String token, String tokenType, String userId, String ssnMasked, long idleTimeoutSeconds,
            Object offers, Object alerts, Object subscriptions) {}

    record SessionResponse(String userId, String ssnMasked) {}

    @PostMapping(path = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<RegisterResponse> register(@Valid @ModelAttribute RegisterForm form) {
        // Checked first, so a registration without consent never costs a KYC call or stores anything.
        if (!Boolean.TRUE.equals(form.authorize())) {
            throw new InvalidRegistrationException(AUTHORIZATION_REQUIRED);
        }
        String userId = auth.register(
                form.userId(), form.password(), form.email(), form.mobilePhone(), form.zip(), bytes(form.ssnCard()));
        AccountSubscriptions paid = subscriptions.getIfAvailable();
        Object idProtection = Boolean.TRUE.equals(form.idProtection()) && paid != null ? paid.atRegistration(userId) : null;
        return ResponseEntity.created(URI.create(BASE + "/session")).body(new RegisterResponse(userId, idProtection));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Step 1: the password. Opens a challenge; never a session. */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    LoginChallengeResponse login(@Valid @RequestBody LoginRequest request) {
        OtpLoginService.Challenge challenge = otp.start(request.userId(), request.password());
        return new LoginChallengeResponse(
                challenge.challengeId(), challenge.channels(), challenge.expiresIn().toSeconds());
    }

    /** Step 2: send a code to the channel the user picked (also used to resend, or to switch channel). */
    @PostMapping(path = "/login/otp", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CodeSentResponse> sendCode(@Valid @RequestBody SendCodeRequest request) {
        OtpChannel channel = OtpChannel.parse(request.channel())
                .orElseThrow(() -> new InvalidOtpRequestException("Choose email or text message."));
        OtpLoginService.CodeSent sent = otp.send(request.challengeId(), channel);
        return ResponseEntity.accepted().body(new CodeSentResponse(sent.channel(), sent.destination(),
                sent.codeExpiresIn().toSeconds(), sent.resendAfter().toSeconds()));
    }

    /** Step 3: the code. Issues the token, with the login extras (offers, alerts). */
    @PostMapping(path = "/login/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    LoginResponse verifyCode(@Valid @RequestBody VerifyCodeRequest request) {
        AuthService.LoginResult result = otp.verify(request.challengeId(), request.code());
        LoginOffers offers = loginOffers.getIfAvailable();
        LoginAlerts alerts = loginAlerts.getIfAvailable();
        AccountSubscriptions paid = subscriptions.getIfAvailable();
        // Optional extras that never throw; asked side by side, so a login waits for the slowest, not
        // their sum.
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var offersFuture = tasks.submit(() -> offers == null ? null : offers.offersFor(result.userId()));
            var alertsFuture = tasks.submit(() -> alerts == null ? null : alerts.alertsFor(result.userId()));
            var subscriptionsFuture = tasks.submit(() -> paid == null ? null : paid.atLogin(result.userId()));
            return new LoginResponse(result.token(), "Bearer", result.userId(), result.ssnMasked(),
                    result.idleTimeout().toSeconds(), extra(offersFuture), extra(alertsFuture), extra(subscriptionsFuture));
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

    @ExceptionHandler(InvalidOtpRequestException.class)
    ResponseEntity<ProblemDetail> invalidOtp(InvalidOtpRequestException e) {
        return OtpProblems.invalid(e);
    }

    /** 401; {@code restart: true} sends the user back to the password, {@code attemptsLeft} when known. */
    @ExceptionHandler(OtpFailedException.class)
    ResponseEntity<ProblemDetail> otpFailed(OtpFailedException e) {
        return OtpProblems.failed(e);
    }

    @ExceptionHandler(TooManyCodesException.class)
    ResponseEntity<ProblemDetail> tooManyCodes(TooManyCodesException e) {
        return OtpProblems.tooMany(e);
    }

    @ExceptionHandler(OtpDeliveryException.class)
    ResponseEntity<ProblemDetail> otpNotSent(OtpDeliveryException e) {
        return OtpProblems.notSent(e);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    ProblemDetail exists(UserAlreadyExistsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SsnAlreadyRegisteredException.class)
    ProblemDetail ssnTaken(SsnAlreadyRegisteredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail emailTaken(EmailAlreadyRegisteredException e) {
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
