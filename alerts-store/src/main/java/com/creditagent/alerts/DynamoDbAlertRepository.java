package com.creditagent.alerts;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * The Alerts table: partition key {@code email}, sort key {@code location}, attribute
 * {@code timestamp} (ISO-8601). One item per email and location.
 */
public class DynamoDbAlertRepository implements AlertRepository {

    public static final String EMAIL = "email";
    public static final String LOCATION = "location";
    public static final String TIMESTAMP = "timestamp";
    /** An email is found at a handful of locations at most; bounds one look-up. */
    static final int MAX_ALERTS = 100;

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbAlertRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public boolean record(Alert alert) {
        try {
            dynamo.putItem(r -> r.tableName(table)
                    .item(Map.of(
                            EMAIL, AttributeValue.fromS(alert.email()),
                            LOCATION, AttributeValue.fromS(alert.location()),
                            TIMESTAMP, AttributeValue.fromS(alert.timestamp().toString())))
                    .conditionExpression("attribute_not_exists(#email)")
                    .expressionAttributeNames(Map.of("#email", EMAIL)));
            return true;
        } catch (ConditionalCheckFailedException exists) {
            return false;
        }
    }

    @Override
    public List<Alert> find(String email) {
        String key = Alert.normalise(email);
        QueryResponse response = dynamo.query(r -> r.tableName(table)
                .keyConditionExpression("#email = :email")
                .expressionAttributeNames(Map.of("#email", EMAIL))
                .expressionAttributeValues(Map.of(":email", AttributeValue.fromS(key)))
                .consistentRead(true)
                .limit(MAX_ALERTS));
        return response.items().stream()
                .map(item -> new Alert(item.get(EMAIL).s(), item.get(LOCATION).s(), Instant.parse(item.get(TIMESTAMP).s())))
                .sorted(Comparator.comparing(Alert::timestamp))
                .toList();
    }
}
