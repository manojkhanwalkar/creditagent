package com.creditagent.notifications;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

/** The DynamoDB client, set up as the other services' are, and creation of the table where wanted. */
public final class NotificationsTable {

    private static final Logger log = LoggerFactory.getLogger(NotificationsTable.class);

    /** Hosts of a DynamoDB on this machine or in compose: the same rule as the web server's. */
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "dynamodb", "dynamodb-local");

    private NotificationsTable() {}

    /**
     * Bounded calls and the SDK's standard retry mode (backoff with jitter on throttling, 5xx and
     * connection errors), within {@code timeout}; each attempt gets half of it.
     *
     * @param endpoint blank = the real AWS endpoint; e.g. {@code http://localhost:8000} for DynamoDB Local
     */
    public static DynamoDbClient client(String endpoint, String region, Duration timeout) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(overrides(timeout));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /** One layer of retries: the SDK's standard mode, all attempts within {@code timeout}. */
    static ClientOverrideConfiguration overrides(Duration timeout) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(timeout)
                .apiCallAttemptTimeout(timeout.dividedBy(2))
                .retryStrategy(RetryMode.STANDARD)
                .build();
    }

    /**
     * True if {@code endpoint} is a DynamoDB on this machine or in compose; false for AWS (an AWS, VPC or
     * FIPS endpoint override included). Guards the development-only log senders.
     */
    static boolean local(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(endpoint.strip()).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Creates the table with its TTL if it is missing (local and test only; in AWS it is infrastructure). */
    public static void ensure(DynamoDbClient dynamo, String table) {
        try {
            dynamo.describeTable(r -> r.tableName(table));
        } catch (ResourceNotFoundException missing) {
            try {
                dynamo.createTable(r -> r.tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(a -> a.attributeName(DynamoDbNotificationStore.KEY)
                                .attributeType(ScalarAttributeType.S))
                        .keySchema(k -> k.attributeName(DynamoDbNotificationStore.KEY).keyType(KeyType.HASH)));
                dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
                log.info("Created DynamoDB table {}", table);
            } catch (ResourceInUseException raced) {
                // Another replica created it first.
            }
        }
        enableTtl(dynamo, table);
    }

    static void enableTtl(DynamoDbClient dynamo, String table) {
        var status = dynamo.describeTimeToLive(r -> r.tableName(table)).timeToLiveDescription();
        if (status != null && (status.timeToLiveStatus() == TimeToLiveStatus.ENABLED
                || status.timeToLiveStatus() == TimeToLiveStatus.ENABLING)) {
            return;
        }
        try {
            dynamo.updateTimeToLive(r -> r.tableName(table).timeToLiveSpecification(
                    t -> t.enabled(true).attributeName(DynamoDbNotificationStore.EXPIRES_AT)));
            log.info("Enabled TTL on DynamoDB table {}", table);
        } catch (DynamoDbException raced) {
            log.info("TTL on {} not changed: {}", table, raced.getClass().getSimpleName());
        }
    }
}
