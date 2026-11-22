package com.creditagent.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The DynamoDB store's conditions, in memory, for unit tests. */
class InMemoryNotificationStore implements NotificationStore {

    final Map<String, Notification> records = new ConcurrentHashMap<>();

    @Override
    public synchronized Optional<Notification> reserve(Notification record, Instant now) {
        Notification held = records.get(record.key());
        if (held == null || (held.status() == Status.PENDING && held.requestHash().equals(record.requestHash())
                && held.leaseUntil().isBefore(now))) {
            records.put(record.key(), record);
            return Optional.empty();
        }
        return Optional.of(held);
    }

    @Override
    public synchronized void markSent(String key, String id, String providerMessageId) {
        records.computeIfPresent(key, (k, held) -> held.id().equals(id) ? held.sent(providerMessageId) : held);
    }

    @Override
    public synchronized void release(String key, String id) {
        records.computeIfPresent(key, (k, held) -> held.id().equals(id) && held.status() == Status.PENDING ? null : held);
    }
}
