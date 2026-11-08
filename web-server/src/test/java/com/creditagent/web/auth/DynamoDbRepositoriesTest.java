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

    static User user(String id, String ssnHash) {
        return new User(id, "$2a$hash", id + "@example.com", "+14155550100", ssnHash, "6789",
                Instant.parse("2026-11-08T12:00:00Z"));
    }

    @Test
    void userIdsAndSsnsAreUnique() {
        User avery = user("avery", "hash-a");
        assertThat(users.create(avery)).isEqualTo(UserRepository.CreateResult.CREATED);
        assertThat(users.create(user("avery", "hash-b"))).isEqualTo(UserRepository.CreateResult.USER_ID_TAKEN);
        assertThat(users.create(user("blake", "hash-a"))).isEqualTo(UserRepository.CreateResult.SSN_TAKEN);
        assertThat(users.find("avery")).contains(avery);
        assertThat(users.find("blake")).isEmpty();
        assertThat(users.find("nobody")).isEmpty();
        assertThat(users.find("ssn#hash-a")).isEmpty();
    }

    @Test
    void sessionsRoundTripAndExpire() {
        // Relative to the real clock: DynamoDB Local applies the TTL and would remove a "live"
        // session whose expiresAt is already in the past.
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Session live = new Session("live", "avery", "hash-a", "6789", now, now, now.plusSeconds(300));
        Session stale = new Session("stale", "avery", "hash-a", "6789", now.minusSeconds(900), now.minusSeconds(600), now.minusSeconds(300));
        sessions.save(live);
        sessions.save(stale);

        assertThat(sessions.find("live")).contains(live);
        assertThat(sessions.deleteExpired(now)).isEqualTo(1);
        assertThat(sessions.find("stale")).isEmpty();

        assertThat(sessions.touch("live", now.plusSeconds(60), now.plusSeconds(360))).isTrue();
        assertThat(sessions.find("live").orElseThrow().expiresAt()).isEqualTo(now.plusSeconds(360));

        sessions.delete("live");
        assertThat(sessions.find("live")).isEmpty();
        // Touching a deleted session must not re-create it.
        assertThat(sessions.touch("live", now, now.plusSeconds(300))).isFalse();
        assertThat(sessions.find("live")).isEmpty();
    }

    @Test
    void sweepingDeletesInBatches() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (int i = 0; i < 60; i++) {
            sessions.save(new Session("old-" + i, "avery", "h", "6789", now.minusSeconds(900), now.minusSeconds(600), now.minusSeconds(1)));
        }
        sessions.save(new Session("keep", "avery", "h", "6789", now, now, now.plusSeconds(300)));
        assertThat(sessions.deleteExpired(now)).isEqualTo(60);
        assertThat(sessions.find("keep")).isPresent();
        assertThat(sessions.find("old-0")).isEmpty();
    }
}
