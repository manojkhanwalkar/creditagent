package com.creditagent.web.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * Sessions table: partition key {@code sessionId} (string). {@code expiresAt} is epoch seconds and
 * is the table's TTL attribute, so DynamoDB also removes sessions that nothing else cleaned up.
 */
public class DynamoDbSessionRepository implements SessionRepository {

    static final String KEY = "sessionId";
    static final String TTL_ATTRIBUTE = "expiresAt";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbSessionRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void save(Session session) {
        dynamo.putItem(r -> r.tableName(table).item(Map.of(
                KEY, AttributeValue.fromS(session.sessionId()),
                "userId", AttributeValue.fromS(session.userId()),
                "createdAt", AttributeValue.fromS(session.createdAt().toString()),
                "lastActivityAt", AttributeValue.fromS(session.lastActivityAt().toString()),
                TTL_ATTRIBUTE, AttributeValue.fromN(Long.toString(session.expiresAt().getEpochSecond())))));
    }

    @Override
    public Optional<Session> find(String sessionId) {
        GetItemResponse response = dynamo.getItem(
                r -> r.tableName(table).key(Map.of(KEY, AttributeValue.fromS(sessionId))).consistentRead(true));
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        return Optional.of(new Session(
                item.get(KEY).s(),
                item.get("userId").s(),
                Instant.parse(item.get("createdAt").s()),
                Instant.parse(item.get("lastActivityAt").s()),
                Instant.ofEpochSecond(Long.parseLong(item.get(TTL_ATTRIBUTE).n()))));
    }

    @Override
    public void delete(String sessionId) {
        dynamo.deleteItem(r -> r.tableName(table).key(Map.of(KEY, AttributeValue.fromS(sessionId))));
    }

    /**
     * A scan with a filter. Fine for the number of live sessions a demo has; at scale, rely on the
     * TTL alone or index sessions by expiry bucket.
     */
    @Override
    public int deleteExpired(Instant now) {
        int deleted = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest.Builder scan = ScanRequest.builder()
                    .tableName(table)
                    .projectionExpression("#id")
                    .filterExpression("#exp <= :now")
                    .expressionAttributeNames(Map.of("#id", KEY, "#exp", TTL_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":now", AttributeValue.fromN(Long.toString(now.getEpochSecond()))));
            if (startKey != null) {
                scan.exclusiveStartKey(startKey);
            }
            ScanResponse page = dynamo.scan(scan.build());
            for (Map<String, AttributeValue> item : page.items()) {
                delete(item.get(KEY).s());
                deleted++;
            }
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty()
                    ? new HashMap<>(page.lastEvaluatedKey())
                    : null;
        } while (startKey != null);
        return deleted;
    }
}
