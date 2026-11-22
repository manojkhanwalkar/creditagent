package com.creditagent.subscriptions;

import java.time.Instant;
import java.util.Objects;

/**
 * One user's subscription to one product.
 *
 * @param startedAt when the current term started
 * @param expiresAt when it ends; the subscription is active before then
 * @param paymentReference the payment provider's reference for the payment that bought this term (no
 *     card data: the provider holds that)
 */
public record Subscription(String userId, Product product, Instant startedAt, Instant expiresAt, String paymentReference) {

    public Subscription {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(paymentReference, "paymentReference");
        if (!expiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("A subscription must expire after it starts");
        }
    }

    /** @return true while {@code now} is before the expiry */
    public boolean activeAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
