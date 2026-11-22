package com.creditagent.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The real table on DynamoDB Local: creation, the conditional activation, look-ups and erasure. */
@Testcontainers(disabledWithoutDocker = true)
class SubscriptionsTableIntegrationTest {

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static DynamoDbClient dynamo;

    @BeforeAll
    static void connect() {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        dynamo = DynamoDbClient.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000))).build();
        assertThat(SubscriptionsTable.ensure(dynamo, "Subscriptions")).isTrue();
        assertThat(SubscriptionsTable.ensure(dynamo, "Subscriptions")).isFalse();
    }

    @AfterAll
    static void close() {
        if (dynamo != null) {
            dynamo.close();
        }
    }

    @Test
    void aTermStartsOnceRenewsAfterItEndsAndIsErased() {
        var store = new DynamoDbSubscriptionRepository(dynamo, "Subscriptions");
        Instant start = Instant.parse("2026-11-21T10:00:00Z");
        Instant end = Instant.parse("2027-11-21T10:00:00Z");
        var first = new Subscription("ada", Product.ID_PROTECTION, start, end, "pay_1");

        assertThat(store.activate(first, "Ada@Example.com")).isTrue();
        assertThat(store.activate(new Subscription("ada", Product.ID_PROTECTION, start.plusSeconds(5), end.plusSeconds(5), "pay_2"),
                "ada@example.com")).isFalse();
        assertThat(store.findAll("ada")).containsExactly(first);
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, start)).isTrue();
        assertThat(store.activeForEmail("ada@example.com", Product.CREDIT_MONITORING, start)).isFalse();
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, end)).isFalse();

        var renewal = new Subscription("ada", Product.ID_PROTECTION, end, end.plusSeconds(86_400 * 365), "pay_3");
        assertThat(store.activate(renewal, "ada@example.com")).isTrue();
        assertThat(store.findAll("ada")).containsExactly(renewal);

        store.eraseAll("ada", "ada@example.com");
        store.eraseAll("ada", "ada@example.com");
        assertThat(store.findAll("ada")).isEmpty();
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, start)).isFalse();
    }
}
