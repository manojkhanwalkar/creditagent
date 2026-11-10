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
}
