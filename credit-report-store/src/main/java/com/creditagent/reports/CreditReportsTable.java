package com.creditagent.reports;

import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.SSESpecification;

/** A bounded DynamoDB client, and creation of the CreditReports table where wanted. */
public final class CreditReportsTable {

    private static final Logger log = LoggerFactory.getLogger(CreditReportsTable.class);

    private CreditReportsTable() {}

    /**
     * Bounded calls and the SDK's standard retry mode (backoff with jitter on throttling, 5xx and
     * connection errors), within {@code timeout}; each attempt gets half of it.
     *
     * @param endpoint blank = the real AWS endpoint; e.g. {@code http://localhost:8000} for DynamoDB Local
     */
    public static DynamoDbClient client(String endpoint, String region, Duration timeout) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout.dividedBy(2))
                        .retryStrategy(RetryMode.STANDARD)
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Creates the table if it is missing (local and test only; in AWS it is infrastructure, and should
     * use a customer-managed KMS key as well as the application-level encryption).
     */
    public static void ensure(DynamoDbClient dynamo, String table) {
        try {
            dynamo.describeTable(r -> r.tableName(table));
        } catch (ResourceNotFoundException missing) {
            try {
                dynamo.createTable(r -> r.tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .sseSpecification(SSESpecification.builder().enabled(true).build())
                        .attributeDefinitions(
                                a -> a.attributeName(DynamoDbCreditReportStore.SSN).attributeType(ScalarAttributeType.S),
                                a -> a.attributeName(DynamoDbCreditReportStore.BUREAU).attributeType(ScalarAttributeType.S))
                        .keySchema(
                                k -> k.attributeName(DynamoDbCreditReportStore.SSN).keyType(KeyType.HASH),
                                k -> k.attributeName(DynamoDbCreditReportStore.BUREAU).keyType(KeyType.RANGE)));
                dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
                log.info("Created DynamoDB table {}", table);
            } catch (ResourceInUseException raced) {
                // The other service created it first.
            }
        }
    }
}
