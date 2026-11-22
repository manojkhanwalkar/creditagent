package com.creditagent.web.subscriptions;

import com.creditagent.subscriptions.Product;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
 * Internal API ({@code InternalApiFilter}: needs {@code INTERNAL_API_TOKEN}): the payments-service
 * starts the term for a payment it confirmed. web-server stays the only writer of the Subscriptions
 * table. Contract: {@code docs/api/subscriptions-internal-v1.yaml}.
 *
 * <p>200 {@code ACTIVE} or {@code ALREADY_SAME_PAYMENT} (safe to repeat), 409 {@code ALREADY_ACTIVE}
 * (another term is active: the caller refunds this payment), 404 unknown user, 400 invalid request,
 * 503 with Retry-After when DynamoDB fails.
 */
@RestController
class SubscriptionActivationController {

    static final String PATH = "/internal/v1/subscriptions/activations";

    private final SubscriptionService subscriptions;

    SubscriptionActivationController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /**
     * @param userId as registration allows it (lower case)
     * @param paymentReference the provider's reference for the payment, 1 to 128 printable characters
     */
    record ActivationRequest(
            @NotNull @Pattern(regexp = "[a-z0-9._-]{1,64}") String userId,
            @NotNull @Size(min = 1, max = 32) String product,
            @NotNull @Pattern(regexp = "[\\x21-\\x7E]{1,128}") String paymentReference) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ActivationResponse(SubscriptionService.ActivationOutcome outcome, Instant expiresAt) {}

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ActivationResponse> activate(@Valid @RequestBody ActivationRequest request) {
        Product product = Product.parse(request.product()).orElseThrow(ProductNotOfferedException::new);
        SubscriptionService.Activation activation =
                subscriptions.activatePaid(request.userId(), product, request.paymentReference());
        ActivationResponse body = new ActivationResponse(activation.outcome(), activation.expiresAt());
        return switch (activation.outcome()) {
            case ACTIVE, ALREADY_SAME_PAYMENT -> ResponseEntity.ok(body);
            case ALREADY_ACTIVE -> ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        };
    }

    @ExceptionHandler(ProductNotOfferedException.class)
    ResponseEntity<ProblemDetail> notOffered(ProductNotOfferedException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(UnknownSubscriberException.class)
    ResponseEntity<ProblemDetail> unknownUser(UnknownSubscriberException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /** DynamoDB failed (after the SDK's own retries): 503 with Retry-After; the caller retries with the same payment. */
    @ExceptionHandler(SdkException.class)
    ResponseEntity<ProblemDetail> storeUnavailable(SdkException e) {
        return SubscriptionController.unavailable("The subscription could not be stored. Please try again shortly.");
    }
}
