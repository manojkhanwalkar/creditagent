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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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

    /**
     * "Now" for session tests, an hour ahead of the real clock. DynamoDB Local applies the TTL against
     * the real clock, so every expiresAt these tests write stays in the real future and only the
     * repository (given this "now") ever treats a row as expired.
     */
    static Instant testNow() {
        return Instant.now().plus(java.time.Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    static User user(String id, String ssnHash) {
        return new User(id, "$2a$hash", id + "@example.com", "+14155550100", ssnHash, "6789", "kyc_" + id,
                Instant.parse("2026-11-08T12:00:00Z"), ReportStatus.UNLOCKED);
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
        Instant now = testNow();
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
        Instant now = testNow();
        for (int i = 0; i < 60; i++) {
            sessions.save(new Session("old-" + i, "avery", "h", "6789", now.minusSeconds(900), now.minusSeconds(600), now.minusSeconds(1)));
        }
        sessions.save(new Session("keep", "avery", "h", "6789", now, now, now.plusSeconds(300)));
        assertThat(sessions.deleteExpired(now)).isEqualTo(60);
        assertThat(sessions.find("keep")).isPresent();
        assertThat(sessions.find("old-0")).isEmpty();
    }

    @Test
    void reportStatusIsStoredAndOnlyExistingUsersCanBeUpdated() {
        users.create(user("dana", "hash-d"));
        assertThat(users.find("dana").orElseThrow().reportStatus()).isEqualTo(ReportStatus.UNLOCKED);

        assertThat(users.updateReportStatus("dana", ReportStatus.LOCKED)).isTrue();
        assertThat(users.find("dana").orElseThrow().reportStatus()).isEqualTo(ReportStatus.LOCKED);

        // Never creates a user, and never touches an SSN claim item.
        assertThat(users.updateReportStatus("ghost", ReportStatus.LOCKED)).isFalse();
        assertThat(users.find("ghost")).isEmpty();
        assertThat(users.updateReportStatus("ssn#hash-d", ReportStatus.LOCKED)).isFalse();
    }

    @Test
    void rowsWithoutAReportStatusReadAsUnlocked() {
        client.putItem(r -> r.tableName("users").item(java.util.Map.of(
                "userId", AttributeValue.fromS("legacy"),
                "passwordHash", AttributeValue.fromS("$2a$hash"),
                "email", AttributeValue.fromS("l@example.com"),
                "phone", AttributeValue.fromS("+14155550100"),
                "ssnHash", AttributeValue.fromS("hash-l"),
                "ssnLastFour", AttributeValue.fromS("6789"),
                "createdAt", AttributeValue.fromS("2026-11-08T12:00:00Z"))));
        assertThat(users.find("legacy").orElseThrow().reportStatus()).isEqualTo(ReportStatus.UNLOCKED);
    }
}
