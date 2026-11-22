package com.creditagent.web.subscriptions;

import com.creditagent.subscriptions.Product;
import com.creditagent.web.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@code GET /api/v1/subscriptions}: the signed-in user's subscriptions and what they can buy.
 * {@code POST /api/v1/subscriptions} with {@code {product}}: buy a year of it. Both need the bearer token.
 * {@code GET /api/v1/subscriptions/plans}: what can be bought and its price, without signing in (the
 * registration form shows it).
 */
@RestController
class SubscriptionController {

    static final String PATH = "/api/v1/subscriptions";
    /** Open (see {@code AuthenticationFilter}): prices only. */
    static final String PLANS_PATH = PATH + "/plans";

    private final SubscriptionService subscriptions;

    SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    record SubscribeRequest(@NotNull @Size(max = 32) String product) {}

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    SubscriptionService.View subscriptions(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return subscriptions.view(user.userId());
    }

    @GetMapping(path = PLANS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    SubscriptionService.Plans plans() {
        return subscriptions.plans();
    }

    /** 201 when a term started, 200 when one was already active, 202 with a checkout URL to pay at. */
    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubscriptionService.SubscribeResult> subscribe(
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @Valid @RequestBody SubscribeRequest request) {
        Product product = Product.parse(request.product()).orElseThrow(ProductNotOfferedException::new);
        SubscriptionService.SubscribeResult result = subscriptions.subscribe(user.userId(), product);
        HttpStatus status = switch (result.outcome()) {
            case ACTIVE -> HttpStatus.CREATED;
            case ALREADY_ACTIVE -> HttpStatus.OK;
            case CHECKOUT -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(result);
    }

    @ExceptionHandler(ProductNotOfferedException.class)
    ResponseEntity<ProblemDetail> notOffered(ProductNotOfferedException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    ResponseEntity<ProblemDetail> declined(PaymentDeclinedException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage()));
    }

    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<ProblemDetail> paymentsUnavailable(PaymentUnavailableException e) {
        return unavailable(e.getMessage());
    }

    /** DynamoDB failed (after the SDK's own retries): 503 with Retry-After. */
    @ExceptionHandler(SdkException.class)
    ResponseEntity<ProblemDetail> storeUnavailable(SdkException e) {
        return unavailable("Subscriptions could not be read. Please try again shortly.");
    }

    static ResponseEntity<ProblemDetail> unavailable(String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail));
    }
}
