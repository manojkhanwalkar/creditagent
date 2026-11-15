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
        return new User(id, "$2a$hash", id + "@example.com", ssnHash, "6789", "kyc_" + id,
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
    void anEmailBelongsToOneAccountWhateverItsCase() {
        assertThat(users.create(user("emery", "hash-email-owner"))).isEqualTo(UserRepository.CreateResult.CREATED);
        User sameEmail = new User("finley", "$2a$hash", "Emery@Example.com", "hash-email-copier", "6789", "kyc_finley",
                Instant.parse("2026-11-08T12:00:00Z"), ReportStatus.UNLOCKED);
        assertThat(users.create(sameEmail)).isEqualTo(UserRepository.CreateResult.EMAIL_TAKEN);
        assertThat(users.find("finley")).isEmpty();
        assertThat(users.find("email#" + DynamoDbUserRepository.emailKey("emery@example.com"))).isEmpty();
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

        // create() is single use per id: another write with the same id is refused...
        Session grant = new Session("grant", "avery", "hash-a", "6789", now, now, now.plusSeconds(3600));
        assertThat(sessions.create(grant)).isTrue();
        Session other = new Session("grant", "avery", "hash-a", "6789", now.plusMillis(1), now, now.plusSeconds(3600));
        assertThat(sessions.create(other)).isFalse();
        assertThat(sessions.find("grant")).contains(grant);
        // ...but the same write repeated (an SDK retry of a put that had been stored) succeeds.
        assertThat(sessions.create(grant)).isTrue();
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

        // Never creates a user.
        assertThat(users.updateReportStatus("ghost", ReportStatus.LOCKED)).isFalse();
        assertThat(users.find("ghost")).isEmpty();
        // Never touches an SSN claim item: the key guard refuses it, and the item is unchanged.
        assertThat(users.updateReportStatus("ssn#hash-d", ReportStatus.LOCKED)).isFalse();
        assertThat(client.getItem(r -> r.tableName("users")
                        .key(java.util.Map.of("userId", AttributeValue.fromS("ssn#hash-d"))).consistentRead(true))
                .item()).doesNotContainKey("reportStatus").containsEntry("owner", AttributeValue.fromS("dana"));
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

    @Test
    void zipAndLastScoreAreStored() {
        User user = new User("zip-user", "$2a$hash", "z@example.com", "hash-zip", "6789", "kyc",
                testNow(), ReportStatus.UNLOCKED, "98101", null, null);
        assertThat(users.create(user)).isEqualTo(UserRepository.CreateResult.CREATED);
        assertThat(users.find("zip-user").orElseThrow().zip()).isEqualTo("98101");
        assertThat(users.updateLastScore("zip-user", 731, testNow())).isTrue();
        assertThat(users.find("zip-user").orElseThrow().lastScore()).isEqualTo(731);
        assertThat(users.updateLastScore("no-such-user", 731, testNow())).isFalse();
    }

    @Test
    void aUserIsFoundByTheirSsnClaim() {
        users.create(user("erin", "hash-e"));
        assertThat(users.findBySsnHash("hash-e")).hasValueSatisfying(u -> assertThat(u.userId()).isEqualTo("erin"));
        assertThat(users.findBySsnHash("hash-unknown")).isEmpty();
        assertThat(users.findBySsnHash("")).isEmpty();
    }

    @Test
    void consentAndLoginAreStoredAndLoginNeverCreatesAUser() {
        Instant at = Instant.parse("2026-11-14T10:00:00Z");
        User user = new User("cora", "$2a$hash", "cora@example.com", "hash-cora", "6789", "kyc", at, ReportStatus.UNLOCKED,
                "98101", null, null, RegistrationPolicy.CONSENT_VERSION, at, null);
        users.create(user);
        assertThat(users.recordLogin("cora", at.plusSeconds(60))).isTrue();
        User stored = users.find("cora").orElseThrow();
        assertThat(stored.consentVersion()).isEqualTo(RegistrationPolicy.CONSENT_VERSION);
        assertThat(stored.consentedAt()).isEqualTo(at);
        assertThat(stored.lastLoginAt()).isEqualTo(at.plusSeconds(60));
        assertThat(users.recordLogin("ghost", at)).isFalse();
        assertThat(users.recordLogin("ssn#hash-cora", at)).isFalse();
        assertThat(users.find("ghost")).isEmpty();
    }

    @Test
    void deleteRemovesTheUserAndItsClaimsButNeverAnotherAccountsClaim() {
        users.create(user("dina", "hash-dina"));
        // Someone else owns the email claim (e.g. an old account without a claim, re-claimed later).
        client.putItem(r -> r.tableName("users").item(java.util.Map.of(
                "userId", AttributeValue.fromS("email#" + DynamoDbUserRepository.emailKey("shared@example.com")),
                "owner", AttributeValue.fromS("other"))));
        User dina = users.find("dina").orElseThrow();

        assertThat(users.delete(dina)).isTrue();
        assertThat(users.find("dina")).isEmpty();
        assertThat(users.findBySsnHash("hash-dina")).isEmpty();
        assertThat(client.getItem(r -> r.tableName("users").key(java.util.Map.of("userId",
                AttributeValue.fromS("email#" + DynamoDbUserRepository.emailKey("dina@example.com"))))).hasItem()
                && !client.getItem(r -> r.tableName("users").key(java.util.Map.of("userId",
                AttributeValue.fromS("email#" + DynamoDbUserRepository.emailKey("dina@example.com"))))).item().isEmpty()).isFalse();
        assertThat(users.delete(dina)).isFalse(); // idempotent
        // The SSN and email are free again.
        assertThat(users.create(user("dina2", "hash-dina"))).isEqualTo(UserRepository.CreateResult.CREATED);

        User sharer = new User("sam", "$2a$hash", "shared@example.com", "hash-sam", "6789", "kyc", testNow(), ReportStatus.UNLOCKED);
        client.putItem(r -> r.tableName("users").item(java.util.Map.of(
                "userId", AttributeValue.fromS("sam"), "passwordHash", AttributeValue.fromS("$2a$hash"),
                "email", AttributeValue.fromS("shared@example.com"), "ssnHash", AttributeValue.fromS("hash-sam"),
                "ssnLastFour", AttributeValue.fromS("6789"), "createdAt", AttributeValue.fromS(testNow().toString()))));
        assertThat(users.delete(sharer)).isTrue();
        assertThat(client.getItem(r -> r.tableName("users").key(java.util.Map.of("userId",
                AttributeValue.fromS("email#" + DynamoDbUserRepository.emailKey("shared@example.com"))))).item())
                .containsEntry("owner", AttributeValue.fromS("other"));
        assertThat(users.delete(user("ssn#x", "h"))).isFalse();
    }

    @Test
    void accountsArePagedSkipClaimsAndReportLegacyPhones() {
        DynamoDbTables.ensure(client, "paged-users", "paged-sessions");
        DynamoDbUserRepository paged = new DynamoDbUserRepository(client, "paged-users");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 7; i++) {
            paged.create(new User("p" + i, "$2a$hash", "p" + i + "@example.com", "hash-p" + i, "6789", "kyc", created,
                    ReportStatus.UNLOCKED));
        }
        paged.recordLogin("p3", created.plusSeconds(3600));
        client.updateItem(r -> r.tableName("paged-users").key(java.util.Map.of("userId", AttributeValue.fromS("p5")))
                .updateExpression("SET phone = :p").expressionAttributeValues(java.util.Map.of(":p", AttributeValue.fromS("+14155550100"))));

        java.util.List<UserRepository.AccountSummary> all = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            UserRepository.Page page = paged.accounts(cursor, 3);
            assertThat(page.accounts().size()).isLessThanOrEqualTo(3);
            all.addAll(page.accounts());
            cursor = page.next();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(all).extracting(UserRepository.AccountSummary::userId)
                .containsExactlyInAnyOrder("p0", "p1", "p2", "p3", "p4", "p5", "p6");
        assertThat(all).filteredOn(a -> a.userId().equals("p3")).first()
                .extracting(UserRepository.AccountSummary::lastActiveAt).isEqualTo(created.plusSeconds(3600));
        assertThat(all).filteredOn(UserRepository.AccountSummary::hasLegacyPhone)
                .extracting(UserRepository.AccountSummary::userId).containsExactly("p5");
        assertThat(all.get(0).toString()).doesNotContain("@example.com");

        paged.removeLegacyPhone("p5");
        paged.removeLegacyPhone("ghost");
        paged.removeLegacyPhone("ssn#x");
        assertThat(client.getItem(r -> r.tableName("paged-users").key(java.util.Map.of("userId", AttributeValue.fromS("p5"))))
                .item()).doesNotContainKey("phone");
        assertThat(paged.find("ghost")).isEmpty();
    }

    @Test
    void deleteAllForUserEndsSessionsAndGrantsButKeepsUsedCodeMarkers() {
        Instant now = testNow();
        java.time.Instant later = now.plusSeconds(600);
        sessions.save(new Session("s-1", "erase-me", "h", "6789", now, now, later));
        sessions.save(new Session("grant-1", "erase-me", "h", "6789", now, now, later));
        sessions.save(new Session(AuthService.USED_CODE_PREFIX + "grant-1", "erase-me", "h", "6789", now, now, later));
        sessions.save(new Session("s-2", "someone-else", "h", "6789", now, now, later));
        assertThat(sessions.deleteAllForUser("erase-me")).isEqualTo(2);
        assertThat(sessions.find("s-1")).isEmpty();
        assertThat(sessions.find("grant-1")).isEmpty();
        assertThat(sessions.find(AuthService.USED_CODE_PREFIX + "grant-1")).isPresent();
        assertThat(sessions.find("s-2")).isPresent();
    }
}
