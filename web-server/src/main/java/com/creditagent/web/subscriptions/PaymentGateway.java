package com.creditagent.web.subscriptions;

import com.creditagent.subscriptions.Product;
import java.net.URI;
import java.util.Objects;

/**
 * Takes the payment for a subscription. The production gateway (Stripe Checkout, a later change)
 * answers with a {@link Redirect} to the provider's hosted payment page, and the term starts when the
 * provider confirms the payment; so card data never reaches this server. A gateway that can charge at
 * once (the test gateway) answers {@link Paid}.
 *
 * <p>The branch-by-abstraction seam for the payments-service extraction (ADR 0002): P2 builds the
 * service behind this contract, and P3 adds a remote implementation selected by config.
 */
public interface PaymentGateway {

    /**
     * The request the planned payments-service takes, so the remote gateway maps one to one.
     *
     * @param amountCents the price in the currency's minor unit
     * @param currency an ISO 4217 code such as {@code USD}
     * @param idempotencyKey the same for repeats of one purchase, so a retried request never charges twice;
     *     passed to the provider unchanged (1 to 128 printable characters)
     * @param returnUrl where a hosted payment page sends the user back to: absolute http(s), or null until
     *     a redirecting provider exists
     */
    record CheckoutRequest(String userId, Product product, long amountCents, String currency, String idempotencyKey,
            URI returnUrl) {

        public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

        public CheckoutRequest {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("A price must be positive");
            }
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be an ISO 4217 code");
            }
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                    || !idempotencyKey.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
                throw new IllegalArgumentException("idempotencyKey must be 1 to 128 printable characters");
            }
            if (returnUrl != null && !isWebUrl(returnUrl)) {
                throw new IllegalArgumentException("returnUrl must be an absolute http(s) URL");
            }
        }

        /** A request without a return URL (no redirecting provider yet). */
        public CheckoutRequest(String userId, Product product, long amountCents, String currency, String idempotencyKey) {
            this(userId, product, amountCents, currency, idempotencyKey, null);
        }

        private static boolean isWebUrl(URI url) {
            String scheme = url.getScheme();
            return url.isAbsolute() && url.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        }
    }

    sealed interface Checkout permits Paid, Redirect {}

    /** The payment was taken: start the term now. */
    record Paid(String paymentReference) implements Checkout {
        public Paid {
            Objects.requireNonNull(paymentReference, "paymentReference");
        }
    }

    /** Send the user to the provider's payment page; the term starts when the provider confirms. */
    record Redirect(URI url) implements Checkout {
        public Redirect {
            Objects.requireNonNull(url, "url");
        }
    }

    /** @return false when no payment provider is configured: nothing can be bought */
    boolean available();

    /**
     * @throws PaymentUnavailableException if the provider cannot be reached or none is configured
     * @throws PaymentDeclinedException if the provider refused the payment
     */
    Checkout checkout(CheckoutRequest request);

    /** No provider configured ({@code PAYMENTS_PROVIDER=none}, the default): subscriptions are shown but cannot be bought. */
    static PaymentGateway none() {
        return new PaymentGateway() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Checkout checkout(CheckoutRequest request) {
                throw new PaymentUnavailableException();
            }
        };
    }

    /**
     * Local development and tests only ({@code PAYMENTS_PROVIDER=test}): every payment succeeds at once and
     * nothing is charged. Refused at start-up unless DynamoDB is local, so it can never give away
     * subscriptions in AWS.
     */
    static PaymentGateway test() {
        return new PaymentGateway() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Checkout checkout(CheckoutRequest request) {
                return new Paid("test_" + java.util.UUID.randomUUID());
            }
        };
    }
}
