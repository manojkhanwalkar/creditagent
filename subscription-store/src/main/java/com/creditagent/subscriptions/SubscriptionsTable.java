package com.creditagent.subscriptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Creates the Subscriptions table where wanted (local and test only; in AWS it is infrastructure). */
public final class SubscriptionsTable {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsTable.class);

    private SubscriptionsTable() {}

    /** @return true if this call created the table */
    public static boolean ensure(DynamoDbClient dynamo, String table) {
        try {
            dynamo.describeTable(r -> r.tableName(table));
            return false;
        } catch (ResourceNotFoundException missing) {
            try {
                dynamo.createTable(r -> r.tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                a -> a.attributeName(DynamoDbSubscriptionRepository.PK).attributeType(ScalarAttributeType.S),
                                a -> a.attributeName(DynamoDbSubscriptionRepository.PRODUCT).attributeType(ScalarAttributeType.S))
                        .keySchema(
                                k -> k.attributeName(DynamoDbSubscriptionRepository.PK).keyType(KeyType.HASH),
                                k -> k.attributeName(DynamoDbSubscriptionRepository.PRODUCT).keyType(KeyType.RANGE)));
                dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
                log.info("Created DynamoDB table {}", table);
                return true;
            } catch (ResourceInUseException raced) {
                return false; // another service created it first
            }
        }
    }
}
