package com.creditagent.web.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Creates the users, sessions and login-challenge tables if they are missing, and turns on TTL for
 * sessions and challenges. Used
 * with DynamoDB Local and in tests ({@code credit.auth.dynamodb.create-tables=true}); in AWS the
 * tables belong to infrastructure code.
 */
public final class DynamoDbTables {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTables.class);

    private DynamoDbTables() {}

    public static void ensure(DynamoDbClient dynamo, String usersTable, String sessionsTable) {
        create(dynamo, usersTable, DynamoDbUserRepository.KEY);
        ensureWithTtl(dynamo, sessionsTable, DynamoDbSessionRepository.KEY, DynamoDbSessionRepository.TTL_ATTRIBUTE);
    }

    /** Creates a table keyed on {@code key} with TTL on {@code ttlAttribute}, if it is missing. */
    public static void ensureWithTtl(DynamoDbClient dynamo, String table, String key, String ttlAttribute) {
        if (create(dynamo, table, key)) {
            dynamo.updateTimeToLive(r -> r.tableName(table).timeToLiveSpecification(
                    t -> t.enabled(true).attributeName(ttlAttribute)));
        }
    }

    /** @return true if this call created the table */
    private static boolean create(DynamoDbClient dynamo, String table, String key) {
        try {
            dynamo.describeTable(r -> r.tableName(table));
            return false;
        } catch (ResourceNotFoundException missing) {
            try {
                dynamo.createTable(r -> r.tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(a -> a.attributeName(key).attributeType(ScalarAttributeType.S))
                        .keySchema(k -> k.attributeName(key).keyType("HASH")));
                dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
                log.info("Created DynamoDB table {}", table);
                return true;
            } catch (ResourceInUseException raced) {
                return false;
            }
        }
    }
}
