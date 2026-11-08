package com.creditagent.web.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/** Users table: partition key {@code userId} (string). */
public class DynamoDbUserRepository implements UserRepository {

    static final String KEY = "userId";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbUserRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public boolean createIfAbsent(User user) {
        try {
            dynamo.putItem(r -> r.tableName(table)
                    .item(Map.of(
                            KEY, s(user.userId()),
                            "passwordHash", s(user.passwordHash()),
                            "email", s(user.email()),
                            "phone", s(user.phone()),
                            "createdAt", s(user.createdAt().toString())))
                    .conditionExpression("attribute_not_exists(#id)")
                    .expressionAttributeNames(Map.of("#id", KEY)));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public Optional<User> find(String userId) {
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
                Instant.parse(item.get("createdAt").s())));
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
