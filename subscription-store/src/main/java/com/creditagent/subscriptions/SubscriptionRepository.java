package com.creditagent.subscriptions;

import java.time.Instant;
import java.util.List;

/** The Subscriptions table. Production implementation: {@link DynamoDbSubscriptionRepository}. */
public interface SubscriptionRepository {

    /** Every subscription the user has had, one per product (the latest term). */
    List<Subscription> findAll(String userId);

    /**
     * Stores the subscription unless the user already has an active one for the product at
     * {@code subscription.startedAt()} (atomically, so two payments cannot both start a term). Also
     * records the product against the user's email, so the alert notifier can tell whether an address
     * belongs to a subscriber without reading the users table.
     *
     * @return true if this call stored it; false if the user was already subscribed
     */
    boolean activate(Subscription subscription, String email);

    /** @return true if the address belongs to a user with an active subscription to the product at {@code now} */
    boolean activeForEmail(String email, Product product, Instant now);

    /** Deletes the user's subscriptions and their email records (account deletion). Idempotent. */
    void eraseAll(String userId, String email);
}
