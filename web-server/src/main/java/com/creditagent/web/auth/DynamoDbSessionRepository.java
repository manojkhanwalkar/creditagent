package com.creditagent.web.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
    private static final int BATCH_SIZE = 25;
    private static final int MAX_BATCH_ATTEMPTS = 3;

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbSessionRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void save(Session session) {
        Map<String, AttributeValue> item = item(session);
        dynamo.putItem(r -> r.tableName(table).item(item));
    }

    @Override
    public boolean create(Session session) {
        Map<String, AttributeValue> item = item(session);
        try {
            dynamo.putItem(r -> r.tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(#id)")
                    .expressionAttributeNames(Map.of("#id", KEY)));
            return true;
        } catch (ConditionalCheckFailedException taken) {
            // The SDK retries a put whose attempt timed out. If the first attempt had in fact
            // succeeded, the retry fails this condition against our own row: that is a success, not
            // a taken id. createdAt is this call's own clock reading, so it tells the two apart.
            return find(session.sessionId())
                    .filter(stored -> stored.userId().equals(session.userId())
                            && stored.createdAt().equals(session.createdAt()))
                    .isPresent();
        }
    }

    private static Map<String, AttributeValue> item(Session session) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, AttributeValue.fromS(session.sessionId()));
        item.put("userId", AttributeValue.fromS(session.userId()));
        if (session.ssnHash() != null) {
            item.put("ssnHash", AttributeValue.fromS(session.ssnHash()));
            item.put("ssnLastFour", AttributeValue.fromS(session.ssnLastFour()));
        }
        item.put("createdAt", AttributeValue.fromS(session.createdAt().toString()));
        item.put("lastActivityAt", AttributeValue.fromS(session.lastActivityAt().toString()));
        item.put(TTL_ATTRIBUTE, AttributeValue.fromN(Long.toString(session.expiresAt().getEpochSecond())));
        return item;
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
                DynamoDbUserRepository.text(item, "ssnHash"),
                DynamoDbUserRepository.text(item, "ssnLastFour"),
                Instant.parse(item.get("createdAt").s()),
                Instant.parse(item.get("lastActivityAt").s()),
                Instant.ofEpochSecond(Long.parseLong(item.get(TTL_ATTRIBUTE).n()))));
    }

    @Override
    public boolean touch(String sessionId, Instant lastActivityAt, Instant expiresAt) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, AttributeValue.fromS(sessionId)))
                    .updateExpression("SET #last = :last, #exp = :exp")
                    .conditionExpression("attribute_exists(#id)")
                    .expressionAttributeNames(Map.of("#id", KEY, "#last", "lastActivityAt", "#exp", TTL_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(
                            ":last", AttributeValue.fromS(lastActivityAt.toString()),
                            ":exp", AttributeValue.fromN(Long.toString(expiresAt.getEpochSecond())))));
            return true;
        } catch (ConditionalCheckFailedException gone) {
            return false;
        }
    }

    @Override
    public void delete(String sessionId) {
        dynamo.deleteItem(r -> r.tableName(table).key(Map.of(KEY, AttributeValue.fromS(sessionId))));
    }

    /** BatchWriteItem in chunks of 25 (DynamoDB's limit), retrying anything it hands back. */
    private void deleteAll(List<String> ids) {
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            List<WriteRequest> batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size())).stream()
                    .map(id -> WriteRequest.builder()
                            .deleteRequest(d -> d.key(Map.of(KEY, AttributeValue.fromS(id))))
                            .build())
                    .toList();
            Map<String, List<WriteRequest>> pending = Map.of(table, batch);
            for (int attempt = 0; !pending.isEmpty() && attempt < MAX_BATCH_ATTEMPTS; attempt++) {
                Map<String, List<WriteRequest>> request = pending;
                BatchWriteItemResponse response = dynamo.batchWriteItem(r -> r.requestItems(request));
                pending = response.hasUnprocessedItems() ? response.unprocessedItems() : Map.of();
            }
            // Anything still pending is left to the next sweep and to the table's TTL.
        }
    }

    /**
     * A scan with a filter, keys only, then batched deletes. It reads the whole table each sweep, which
     * suits the number of sessions a demo has; at scale, rely on the TTL (authenticate() already
     * refuses expired sessions) or index sessions by expiry bucket.
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
            List<String> ids = page.items().stream().map(item -> item.get(KEY).s()).toList();
            deleteAll(ids);
            deleted += ids.size();
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty()
                    ? new HashMap<>(page.lastEvaluatedKey())
                    : null;
        } while (startKey != null);
        return deleted;
    }
}
