package com.creditagent.web.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Users table: partition key {@code userId} (string).
 *
 * <p>Each user has a second item, {@code ssn#<hash>}, that claims their SSN. Both items are written
 * in one conditional transaction, so neither a user id nor an SSN can be registered twice, even by
 * concurrent requests. User ids cannot contain {@code #}, so a claim never collides with a user.
 */
public class DynamoDbUserRepository implements UserRepository {

    static final String KEY = "userId";
    static final String SSN_CLAIM_PREFIX = "ssn#";
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbUserRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public CreateResult create(User user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, s(user.userId()));
        item.put("passwordHash", s(user.passwordHash()));
        item.put("email", s(user.email()));
        item.put("phone", s(user.phone()));
        item.put("ssnHash", s(user.ssnHash()));
        item.put("ssnLastFour", s(user.ssnLastFour()));
        item.put("createdAt", s(user.createdAt().toString()));
        Map<String, AttributeValue> claim = Map.of(
                KEY, s(SSN_CLAIM_PREFIX + user.ssnHash()),
                "owner", s(user.userId()));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(
                    t -> t.put(p -> p.tableName(table).item(item)
                            .conditionExpression("attribute_not_exists(#id)")
                            .expressionAttributeNames(Map.of("#id", KEY))),
                    t -> t.put(p -> p.tableName(table).item(claim)
                            .conditionExpression("attribute_not_exists(#id)")
                            .expressionAttributeNames(Map.of("#id", KEY)))));
            return CreateResult.CREATED;
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            if (reasons.size() == 2) {
                if (CONDITIONAL_CHECK_FAILED.equals(reasons.get(0).code())) {
                    return CreateResult.USER_ID_TAKEN;
                }
                if (CONDITIONAL_CHECK_FAILED.equals(reasons.get(1).code())) {
                    return CreateResult.SSN_TAKEN;
                }
            }
            throw e;
        }
    }

    @Override
    public Optional<User> find(String userId) {
        if (userId == null || userId.startsWith(SSN_CLAIM_PREFIX)) {
            return Optional.empty();
        }
        GetItemResponse response = dynamo.getItem(r -> r.tableName(table).key(Map.of(KEY, s(userId))).consistentRead(true));
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        return Optional.of(new User(
                item.get(KEY).s(),
                item.get("passwordHash").s(),
                item.get("email").s(),
                item.get("phone").s(),
                text(item, "ssnHash"),
                text(item, "ssnLastFour"),
                Instant.parse(item.get("createdAt").s())));
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    /** A string attribute that rows written before it existed may lack. */
    static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }
}
