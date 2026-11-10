package com.creditagent.alerts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * The addresses to watch: registered users' emails, read from the users table a page at a time. Only
 * the {@code email} attribute is read, and only from user items (they have a password hash; SSN
 * claim items do not). Watching only registered users means nothing is stored about anyone else.
 */
public class WatchList {

    @FunctionalInterface
    public interface Source {
        /**
         * Hands each page of addresses to {@code page}, reading at most {@code max} items. A cycle that
         * stops at {@code max} leaves a cursor, and the next cycle resumes there, so with more users
         * than {@code max} every user is still reached in turn.
         *
         * @return how many items were read
         */
        int forEachPage(int pageSize, int max, Consumer<List<String>> page);
    }

    private WatchList() {}

    public static Source users(DynamoDbClient dynamo, String usersTable) {
        Objects.requireNonNull(dynamo);
        Objects.requireNonNull(usersTable);
        java.util.concurrent.atomic.AtomicReference<Map<String, AttributeValue>> cursor = new java.util.concurrent.atomic.AtomicReference<>();
        return (pageSize, max, page) -> {
            int seen = 0;
            Map<String, AttributeValue> startKey = cursor.getAndSet(null);
            try {
                do {
                    ScanRequest.Builder scan = ScanRequest.builder()
                            .tableName(usersTable)
                            .projectionExpression("#email")
                            .filterExpression("attribute_exists(#pw) AND attribute_exists(#email)")
                            .expressionAttributeNames(Map.of("#email", "email", "#pw", "passwordHash"))
                            .limit(Math.min(pageSize, max - seen));
                    if (startKey != null) {
                        scan.exclusiveStartKey(startKey);
                    }
                    ScanResponse response = dynamo.scan(scan.build());
                    List<String> emails = response.items().stream().map(item -> Alert.normalise(item.get("email").s())).toList();
                    if (!emails.isEmpty()) {
                        page.accept(emails);
                    }
                    seen += response.scannedCount();
                    startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                            ? new HashMap<>(response.lastEvaluatedKey()) : null;
                } while (startKey != null && seen < max);
            } finally {
                // Stopped early, or a page failed: the next cycle continues from here (the failed page
                // is read again). Reached the end: it starts over.
                cursor.set(startKey);
            }
            return seen;
        };
    }
}
