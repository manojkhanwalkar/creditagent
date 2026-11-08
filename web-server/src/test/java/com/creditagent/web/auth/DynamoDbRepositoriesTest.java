package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The DynamoDB repositories against DynamoDB Local. Skipped where Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbRepositoriesTest {

    static final String IMAGE = "amazon/dynamodb-local:3.3.2";

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(IMAGE)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static DynamoDbClient client;
    static DynamoDbUserRepository users;
    static DynamoDbSessionRepository sessions;

    @BeforeAll
    static void connect() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        DynamoDbTables.ensure(client, "users", "sessions");
        DynamoDbTables.ensure(client, "users", "sessions"); // idempotent
        users = new DynamoDbUserRepository(client, "users");
        sessions = new DynamoDbSessionRepository(client, "sessions");
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void userIdsAreUnique() {
        User user = new User("avery", "$2a$hash", "avery@example.com", "+14155550100", Instant.parse("2026-11-08T12:00:00Z"));
        assertThat(users.createIfAbsent(user)).isTrue();
        assertThat(users.createIfAbsent(user)).isFalse();
        assertThat(users.find("avery")).contains(user);
        assertThat(users.find("nobody")).isEmpty();
    }

    @Test
    void sessionsRoundTripAndExpire() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        Session live = new Session("live", "avery", now, now, now.plusSeconds(300));
        Session stale = new Session("stale", "avery", now.minusSeconds(900), now.minusSeconds(600), now.minusSeconds(300));
        sessions.save(live);
        sessions.save(stale);

        assertThat(sessions.find("live")).contains(live);
        assertThat(sessions.deleteExpired(now)).isEqualTo(1);
        assertThat(sessions.find("stale")).isEmpty();

        sessions.delete("live");
        assertThat(sessions.find("live")).isEmpty();
    }
}
