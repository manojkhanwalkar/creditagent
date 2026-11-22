package com.creditagent.notifications;

import java.time.Instant;
import java.util.Optional;

/** Remembers accepted sends by idempotency key. */
public interface NotificationStore {

    /**
     * Where a remembered send is: PENDING while it runs, SENT when the provider accepted it, UNCERTAIN
     * when the provider's answer was lost (a timeout, 5xx or throttling: it may have been delivered).
     */
    enum Status { PENDING, SENT, UNCERTAIN }

    /**
     * One remembered send. Holds no code, message text or full destination.
     *
     * @param key the keyed hash of the caller's idempotency key (the raw key can carry a challenge id)
     * @param requestHash keyed hash of the canonical request, to tell a repeat from a different request
     * @param id this service's id for the send, returned in the 202
     * @param destination the masked destination
     * @param leaseUntil until when a PENDING record belongs to the request that wrote it
     * @param expiresAt when DynamoDB's TTL deletes the record
     */
    record Notification(String key, String requestHash, String id, Channel channel, Template template,
            String destination, Status status, String providerMessageId, Instant createdAt, Instant leaseUntil,
            Instant expiresAt) {

        Notification sent(String providerId) {
            return new Notification(key, requestHash, id, channel, template, destination, Status.SENT, providerId,
                    createdAt, leaseUntil, expiresAt);
        }

        Notification withStatus(Status newStatus) {
            return new Notification(key, requestHash, id, channel, template, destination, newStatus, providerMessageId,
                    createdAt, leaseUntil, expiresAt);
        }
    }

    /**
     * Writes {@code record} as PENDING if no record has its key, or if the one there is PENDING with the
     * same request and an expired lease (its sender stopped mid-send).
     *
     * @return empty when this call now owns the key; else the record that holds it
     */
    Optional<Notification> reserve(Notification record, Instant now);

    /** The send went out: SENT, with the provider's id. Only the reservation's own {@code id} is changed. */
    void markSent(String key, String id, String providerMessageId);

    /**
     * The outcome is unknown: UNCERTAIN, so the key is never sent again (the message may have arrived).
     * Only the reservation's own {@code id} is changed.
     */
    void markUncertain(String key, String id);

    /** The provider clearly refused: deletes this reservation (only if it is still {@code id}'s PENDING one), so a retry can send. */
    void release(String key, String id);
}
