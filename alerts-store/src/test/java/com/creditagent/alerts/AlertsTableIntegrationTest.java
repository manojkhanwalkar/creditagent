package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The real table on DynamoDB Local: creation, the conditional put and the query. */
@Testcontainers(disabledWithoutDocker = true)
class AlertsTableIntegrationTest {

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static DynamoDbClient dynamo;

    @BeforeAll
    static void connect() {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        dynamo = AlertsTable.client("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000), "us-east-1",
                Duration.ofSeconds(10));
        AlertsTable.ensure(dynamo, "Alerts");
        AlertsTable.ensure(dynamo, "Alerts"); // a second start finds it there
    }

    @AfterAll
    static void close() {
        if (dynamo != null) {
            dynamo.close();
        }
    }

    @Test
    void eachEmailAndLocationIsStoredOnceAndFoundByEmail() {
        DynamoDbAlertRepository alerts = new DynamoDbAlertRepository(dynamo, "Alerts");
        Instant now = Instant.parse("2026-11-09T12:00:00Z");
        assertThat(alerts.record(new Alert("Ada@Example.com", "paste-site-alpha", now))).isTrue();
        assertThat(alerts.record(new Alert("ada@example.com", "paste-site-alpha", now.plusSeconds(60)))).isFalse();
        assertThat(alerts.record(new Alert("ada@example.com", "breach-forum-beta", now.plusSeconds(30)))).isTrue();
        assertThat(alerts.find("ADA@example.com")).extracting(Alert::location).containsExactly("paste-site-alpha", "breach-forum-beta");
        assertThat(alerts.find("nobody@example.com")).isEmpty();
        assertThat(AwsBasicCredentials.create("local", "local")).isNotNull();
    }

    @Test
    void theNotifierSeesEachUnnotifiedAlertOfTheWindowOnceAcrossDays() {
        DynamoDbAlertRepository alerts = new DynamoDbAlertRepository(dynamo, "Alerts");
        Instant run = Instant.parse("2026-11-12T08:00:00Z");
        Instant from = run.minus(Duration.ofHours(24));
        alerts.record(new Alert("old@example.com", "loc-a", from.minusSeconds(1)));         // before the window
        alerts.record(new Alert("eve@example.com", "loc-a", from));                          // first instant: in
        alerts.record(new Alert("eve@example.com", "loc-b", Instant.parse("2026-11-12T01:00:00Z"))); // the next UTC day
        alerts.record(new Alert("late@example.com", "loc-a", run));                          // the run's own instant: out

        assertThat(alerts.unnotifiedBetween(from, run, 100)).extracting(Alert::email, Alert::location)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("eve@example.com", "loc-a"),
                        org.assertj.core.groups.Tuple.tuple("eve@example.com", "loc-b"));
        assertThat(alerts.unnotifiedBetween(from, run, 1)).hasSize(1);

        Alert first = alerts.unnotifiedBetween(from, run, 100).get(0);
        assertThat(alerts.claimNotification(first, run)).isTrue();
        assertThat(alerts.claimNotification(first, run.plusSeconds(1))).isFalse(); // a second replica or run
        assertThat(alerts.unnotifiedBetween(from, run, 100)).extracting(Alert::location).containsExactly("loc-b");

        alerts.releaseNotification(first, run.plusSeconds(1)); // not that run's claim: kept
        assertThat(alerts.unnotifiedBetween(from, run, 100)).hasSize(1);
        alerts.releaseNotification(first, run);
        assertThat(alerts.unnotifiedBetween(from, run, 100)).hasSize(2);

        // A claim never creates an alert.
        assertThat(alerts.claimNotification(new Alert("ghost@example.com", "loc-a", from), run)).isFalse();
        assertThat(alerts.find("ghost@example.com")).isEmpty();
        assertThat(alerts.unnotifiedBetween(run, from, 100)).isEmpty();
    }

    @Test
    void aTableFromBeforeTheIndexGetsIt() {
        dynamo.createTable(r -> r.tableName("OldAlerts")
                .billingMode(software.amazon.awssdk.services.dynamodb.model.BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        a -> a.attributeName("email").attributeType(software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType.S),
                        a -> a.attributeName("location").attributeType(software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("email").keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH),
                        k -> k.attributeName("location").keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.RANGE)));
        AlertsTable.ensure(dynamo, "OldAlerts");
        assertThat(dynamo.describeTable(r -> r.tableName("OldAlerts")).table().globalSecondaryIndexes())
                .extracting(index -> index.indexName()).containsExactly(DynamoDbAlertRepository.BY_DAY);
    }
}
