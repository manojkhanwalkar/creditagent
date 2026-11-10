package com.creditagent.alerts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * The Alerts table: partition key {@code email}, sort key {@code location}, attributes
 * {@code timestamp} (ISO-8601), {@code day} (its UTC date, {@code yyyy-MM-dd}) and, once an email has
 * gone out about it, {@code notifiedAt}. One item per email and location.
 *
 * <p>Global secondary index {@value #BY_DAY}: partition key {@code day}, sort key {@code timestamp}, so
 * the notifier reads the last 24 hours with one query per day touched instead of scanning the table.
 */
public class DynamoDbAlertRepository implements AlertRepository {

    public static final String EMAIL = "email";
    public static final String LOCATION = "location";
    public static final String TIMESTAMP = "timestamp";
    public static final String DAY = "day";
    public static final String NOTIFIED_AT = "notifiedAt";
    public static final String BY_DAY = "by-day";
    private static final int PAGE = 200;
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
                            TIMESTAMP, AttributeValue.fromS(alert.timestamp().toString()),
                            DAY, AttributeValue.fromS(day(alert.timestamp()))))
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
                .map(DynamoDbAlertRepository::alert)
                .sorted(Comparator.comparing(Alert::timestamp))
                .toList();
    }

    @Override
    public List<Alert> unnotifiedBetween(Instant from, Instant to, int max) {
        if (!from.isBefore(to) || max < 1) {
            return List.of();
        }
        List<Alert> found = new ArrayList<>();
        // One query per UTC day the window touches (two for a 24-hour window), each paged.
        for (LocalDate date = LocalDate.ofInstant(from, ZoneOffset.UTC);
                !date.isAfter(LocalDate.ofInstant(to, ZoneOffset.UTC)) && found.size() < max; date = date.plusDays(1)) {
            Map<String, AttributeValue> startKey = null;
            do {
                int room = Math.min(PAGE, max - found.size());
                QueryRequest.Builder query = QueryRequest.builder().tableName(table).indexName(BY_DAY)
                        .keyConditionExpression("#day = :day AND #ts BETWEEN :from AND :to")
                        .filterExpression("attribute_not_exists(#notified)")
                        .expressionAttributeNames(Map.of("#day", DAY, "#ts", TIMESTAMP, "#notified", NOTIFIED_AT))
                        .expressionAttributeValues(Map.of(
                                ":day", AttributeValue.fromS(date.toString()),
                                ":from", AttributeValue.fromS(from.toString()),
                                // BETWEEN is inclusive; the window is [from, to).
                                ":to", AttributeValue.fromS(to.minusNanos(1).toString())))
                        .limit(room);
                if (startKey != null) {
                    query.exclusiveStartKey(startKey);
                }
                QueryResponse page = dynamo.query(query.build());
                page.items().stream().map(DynamoDbAlertRepository::alert)
                        .filter(alert -> !alert.timestamp().isBefore(from) && alert.timestamp().isBefore(to))
                        .limit(max - found.size())
                        .forEach(found::add);
                startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
            } while (startKey != null && found.size() < max);
        }
        found.sort(Comparator.comparing(Alert::timestamp));
        return found;
    }

    @Override
    public boolean claimNotification(Alert alert, Instant runAt) {
        try {
            dynamo.updateItem(r -> r.tableName(table).key(key(alert))
                    .updateExpression("SET #notified = :run")
                    // The item must exist (never create one) and be unclaimed.
                    .conditionExpression("attribute_exists(#email) AND attribute_not_exists(#notified)")
                    .expressionAttributeNames(Map.of("#notified", NOTIFIED_AT, "#email", EMAIL))
                    .expressionAttributeValues(Map.of(":run", AttributeValue.fromS(runAt.toString()))));
            return true;
        } catch (ConditionalCheckFailedException taken) {
            return false;
        }
    }

    @Override
    public void releaseNotification(Alert alert, Instant runAt) {
        try {
            dynamo.updateItem(r -> r.tableName(table).key(key(alert))
                    .updateExpression("REMOVE #notified")
                    // Only this run's own claim.
                    .conditionExpression("#notified = :run")
                    .expressionAttributeNames(Map.of("#notified", NOTIFIED_AT))
                    .expressionAttributeValues(Map.of(":run", AttributeValue.fromS(runAt.toString()))));
        } catch (ConditionalCheckFailedException notOurs) {
            // Already released, or claimed by someone else: leave it.
        }
    }

    static String day(Instant timestamp) {
        return LocalDate.ofInstant(timestamp, ZoneOffset.UTC).toString();
    }

    private static Map<String, AttributeValue> key(Alert alert) {
        return Map.of(EMAIL, AttributeValue.fromS(alert.email()), LOCATION, AttributeValue.fromS(alert.location()));
    }

    private static Alert alert(Map<String, AttributeValue> item) {
        return new Alert(item.get(EMAIL).s(), item.get(LOCATION).s(), Instant.parse(item.get(TIMESTAMP).s()));
    }
}
