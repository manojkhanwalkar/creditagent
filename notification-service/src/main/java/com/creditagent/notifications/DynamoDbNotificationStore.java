package com.creditagent.notifications;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;

/**
 * The {@code credit-agent-notifications} table, keyed by {@value #KEY}, with a TTL on {@value #EXPIRES_AT}.
 * Every write is conditional, so two replicas never both own one key.
 */
public final class DynamoDbNotificationStore implements NotificationStore {

    static final String KEY = "idempotencyKey";
    static final String REQUEST_HASH = "requestHash";
    static final String ID = "id";
    static final String CHANNEL = "channel";
    static final String TEMPLATE = "template";
    static final String DESTINATION = "destination";
    static final String STATUS = "status";
    static final String PROVIDER_MESSAGE_ID = "providerMessageId";
    static final String CREATED_AT = "createdAt";
    static final String LEASE_UNTIL = "leaseUntil";
    static final String EXPIRES_AT = "expiresAt";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbNotificationStore(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public Optional<Notification> reserve(Notification record, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, s(record.key()));
        item.put(REQUEST_HASH, s(record.requestHash()));
        item.put(ID, s(record.id()));
        item.put(CHANNEL, s(record.channel().name()));
        item.put(TEMPLATE, s(record.template().name()));
        item.put(DESTINATION, s(record.destination()));
        item.put(STATUS, s(Status.PENDING.name()));
        item.put(CREATED_AT, s(record.createdAt().toString()));
        item.put(LEASE_UNTIL, n(record.leaseUntil().toEpochMilli()));
        item.put(EXPIRES_AT, n(record.expiresAt().getEpochSecond()));
        try {
            dynamo.putItem(r -> r.tableName(table).item(item)
                    .conditionExpression("attribute_not_exists(#k) OR (#s = :pending AND #h = :hash AND #l < :now)")
                    .expressionAttributeNames(Map.of("#k", KEY, "#s", STATUS, "#h", REQUEST_HASH, "#l", LEASE_UNTIL))
                    .expressionAttributeValues(Map.of(":pending", s(Status.PENDING.name()),
                            ":hash", s(record.requestHash()), ":now", n(now.toEpochMilli())))
                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD));
            return Optional.empty();
        } catch (ConditionalCheckFailedException held) {
            Map<String, AttributeValue> old = held.hasItem() ? held.item() : null;
            if (old == null || old.isEmpty()) {
                old = dynamo.getItem(r -> r.tableName(table).key(Map.of(KEY, s(record.key()))).consistentRead(true)).item();
            }
            if (old == null || old.isEmpty()) {
                // Released between the put and the read: the caller may try again at once.
                return Optional.of(new Notification(record.key(), record.requestHash(), record.id(), record.channel(),
                        record.template(), record.destination(), Status.PENDING, null, now, now, record.expiresAt()));
            }
            return Optional.of(from(old));
        }
    }

    @Override
    public void markSent(String key, String id, String providerMessageId) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":sent", s(Status.SENT.name()));
        values.put(":id", s(id));
        values.put(":pid", s(providerMessageId == null ? "" : providerMessageId));
        try {
            dynamo.updateItem(r -> r.tableName(table).key(Map.of(KEY, s(key)))
                    .updateExpression("SET #s = :sent, #p = :pid")
                    .conditionExpression("#i = :id")
                    .expressionAttributeNames(Map.of("#s", STATUS, "#p", PROVIDER_MESSAGE_ID, "#i", ID))
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException taken) {
            // Another request took over this key after our lease ran out; its record stands.
        }
    }

    @Override
    public void markUncertain(String key, String id) {
        try {
            dynamo.updateItem(r -> r.tableName(table).key(Map.of(KEY, s(key)))
                    .updateExpression("SET #s = :uncertain")
                    .conditionExpression("#i = :id")
                    .expressionAttributeNames(Map.of("#s", STATUS, "#i", ID))
                    .expressionAttributeValues(Map.of(":uncertain", s(Status.UNCERTAIN.name()), ":id", s(id))));
        } catch (ConditionalCheckFailedException taken) {
            // Another request took over this key after our lease ran out; its record stands.
        }
    }

    @Override
    public void release(String key, String id) {
        try {
            dynamo.deleteItem(r -> r.tableName(table).key(Map.of(KEY, s(key)))
                    .conditionExpression("#i = :id AND #s = :pending")
                    .expressionAttributeNames(Map.of("#i", ID, "#s", STATUS))
                    .expressionAttributeValues(Map.of(":id", s(id), ":pending", s(Status.PENDING.name()))));
        } catch (ConditionalCheckFailedException taken) {
            // Already gone, or another request owns the key now: nothing of ours to delete.
        }
    }

    static Notification from(Map<String, AttributeValue> item) {
        AttributeValue provider = item.get(PROVIDER_MESSAGE_ID);
        return new Notification(item.get(KEY).s(), item.get(REQUEST_HASH).s(), item.get(ID).s(),
                Channel.valueOf(item.get(CHANNEL).s()), Template.valueOf(item.get(TEMPLATE).s()),
                item.get(DESTINATION).s(), Status.valueOf(item.get(STATUS).s()),
                provider == null || provider.s() == null || provider.s().isEmpty() ? null : provider.s(),
                Instant.parse(item.get(CREATED_AT).s()), Instant.ofEpochMilli(Long.parseLong(item.get(LEASE_UNTIL).n())),
                Instant.ofEpochSecond(Long.parseLong(item.get(EXPIRES_AT).n())));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
