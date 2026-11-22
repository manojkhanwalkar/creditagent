package com.creditagent.subscriptions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * The Subscriptions table: partition key {@code pk}, sort key {@code product}. Two kinds of item:
 *
 * <ul>
 *   <li>{@code pk = <userId>}: the user's latest term for the product, with {@code startedAt},
 *       {@code expiresAt} (ISO-8601), {@code expiresAtMillis} (for conditions) and
 *       {@code paymentReference}.</li>
 *   <li>{@code pk = email#<sha-256 of the address>}: the same term against the user's email, with
 *       {@code owner} and {@code expiresAtMillis}, so the alert notifier, which knows only addresses,
 *       can tell a subscriber without reading the users table.</li>
 * </ul>
 *
 * <p>Items are not expired by TTL: an ended term stays as the record of what was bought, until the
 * account is deleted.
 */
public class DynamoDbSubscriptionRepository implements SubscriptionRepository {

    public static final String PK = "pk";
    public static final String PRODUCT = "product";
    static final String STARTED_AT = "startedAt";
    static final String EXPIRES_AT = "expiresAt";
    static final String EXPIRES_AT_MILLIS = "expiresAtMillis";
    static final String PAYMENT_REFERENCE = "paymentReference";
    static final String OWNER = "owner";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbSubscriptionRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public List<Subscription> findAll(String userId) {
        requireUserId(userId);
        QueryResponse response = dynamo.query(r -> r.tableName(table)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", PK))
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(userId)))
                .consistentRead(true));
        List<Subscription> found = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            Product.parse(item.get(PRODUCT).s()).ifPresent(product -> found.add(new Subscription(userId, product,
                    Instant.parse(item.get(STARTED_AT).s()), Instant.parse(item.get(EXPIRES_AT).s()),
                    item.get(PAYMENT_REFERENCE).s())));
        }
        return List.copyOf(found);
    }

    /**
     * One transaction: the user's item, only if absent or expired at the new term's start, and the email
     * item. If the condition fails, neither is written.
     */
    @Override
    public boolean activate(Subscription subscription, String email) {
        requireUserId(subscription.userId());
        String expiresMillis = Long.toString(subscription.expiresAt().toEpochMilli());
        Put user = Put.builder().tableName(table)
                .item(Map.of(
                        PK, AttributeValue.fromS(subscription.userId()),
                        PRODUCT, AttributeValue.fromS(subscription.product().name()),
                        STARTED_AT, AttributeValue.fromS(subscription.startedAt().toString()),
                        EXPIRES_AT, AttributeValue.fromS(subscription.expiresAt().toString()),
                        EXPIRES_AT_MILLIS, AttributeValue.fromN(expiresMillis),
                        PAYMENT_REFERENCE, AttributeValue.fromS(subscription.paymentReference())))
                .conditionExpression("attribute_not_exists(#pk) OR #expires <= :start")
                .expressionAttributeNames(Map.of("#pk", PK, "#expires", EXPIRES_AT_MILLIS))
                .expressionAttributeValues(Map.of(":start",
                        AttributeValue.fromN(Long.toString(subscription.startedAt().toEpochMilli()))))
                .build();
        Put byEmail = Put.builder().tableName(table)
                .item(Map.of(
                        PK, AttributeValue.fromS(SubscriptionKeys.emailKey(email)),
                        PRODUCT, AttributeValue.fromS(subscription.product().name()),
                        OWNER, AttributeValue.fromS(subscription.userId()),
                        EXPIRES_AT_MILLIS, AttributeValue.fromN(expiresMillis)))
                .build();
        try {
            dynamo.transactWriteItems(r -> r.transactItems(
                    TransactWriteItem.builder().put(user).build(), TransactWriteItem.builder().put(byEmail).build()));
            return true;
        } catch (TransactionCanceledException e) {
            if (e.hasCancellationReasons() && !e.cancellationReasons().isEmpty()
                    && "ConditionalCheckFailed".equals(reason(e.cancellationReasons().get(0)))) {
                return false;
            }
            throw e;
        }
    }

    private static String reason(CancellationReason reason) {
        return reason == null ? null : reason.code();
    }

    @Override
    public boolean activeForEmail(String email, Product product, Instant now) {
        GetItemResponse response = dynamo.getItem(r -> r.tableName(table)
                .key(Map.of(PK, AttributeValue.fromS(SubscriptionKeys.emailKey(email)),
                        PRODUCT, AttributeValue.fromS(product.name())))
                .projectionExpression("#expires")
                .expressionAttributeNames(Map.of("#expires", EXPIRES_AT_MILLIS)));
        if (!response.hasItem() || response.item().isEmpty() || response.item().get(EXPIRES_AT_MILLIS) == null) {
            return false;
        }
        return now.toEpochMilli() < Long.parseLong(response.item().get(EXPIRES_AT_MILLIS).n());
    }

    /** Deletes the user's item and the email item for every product; a missing item is not an error. */
    @Override
    public void eraseAll(String userId, String email) {
        requireUserId(userId);
        for (Product product : Product.values()) {
            dynamo.deleteItem(r -> r.tableName(table).key(Map.of(
                    PK, AttributeValue.fromS(userId), PRODUCT, AttributeValue.fromS(product.name()))));
            if (email != null) {
                dynamo.deleteItem(r -> r.tableName(table).key(Map.of(
                        PK, AttributeValue.fromS(SubscriptionKeys.emailKey(email)), PRODUCT, AttributeValue.fromS(product.name()))));
            }
        }
    }

    /** A user id with {@code #} could name an email item; registration never allows one. */
    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.contains("#")) {
            throw new IllegalArgumentException("Not a user id");
        }
    }
}
