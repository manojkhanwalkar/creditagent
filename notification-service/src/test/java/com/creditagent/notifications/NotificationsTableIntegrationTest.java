package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.notifications.NotificationStore.Notification;
import com.creditagent.notifications.NotificationStore.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** The real table on DynamoDB Local: creation with TTL, the conditional reserve, mark-sent and release. */
@Testcontainers(disabledWithoutDocker = true)
class NotificationsTableIntegrationTest {

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static final String TABLE = "credit-agent-notifications";
    static DynamoDbClient dynamo;

    @BeforeAll
    static void connect() {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        dynamo = NotificationsTable.client("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000), "us-east-1",
                Duration.ofSeconds(10));
        NotificationsTable.ensure(dynamo, TABLE);
        NotificationsTable.ensure(dynamo, TABLE); // a second start finds it there
    }

    @AfterAll
    static void close() {
        if (dynamo != null) {
            dynamo.close();
        }
    }

    static Notification pending(String key, String hash, String id, Instant now) {
        return new Notification(key, hash, id, Channel.EMAIL, Template.OTP_SIGN_IN, "a***@example.com", Status.PENDING,
                null, now, now.plusSeconds(60), now.plus(Duration.ofDays(30)));
    }

    @Test
    void aKeyIsOwnedOnceThenAnsweredFromTheRecordAndTheTtlIsOn() {
        DynamoDbNotificationStore store = new DynamoDbNotificationStore(dynamo, TABLE);
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        assertThat(store.reserve(pending("key-1", "hash-1", "id-1", now), now)).isEmpty();
        Notification held = store.reserve(pending("key-1", "hash-1", "id-2", now), now.plusSeconds(1)).orElseThrow();
        assertThat(held.id()).isEqualTo("id-1");
        assertThat(held.status()).isEqualTo(Status.PENDING);

        store.markSent("key-1", "id-2", "not-ours"); // another request's id: ignored
        store.markSent("key-1", "id-1", "ses-1");
        Notification sent = store.reserve(pending("key-1", "hash-1", "id-3", now), now.plusSeconds(120)).orElseThrow();
        assertThat(sent.status()).isEqualTo(Status.SENT);
        assertThat(sent.providerMessageId()).isEqualTo("ses-1");
        store.release("key-1", "id-1"); // SENT is never released
        assertThat(store.reserve(pending("key-1", "hash-1", "id-4", now), now).orElseThrow().status()).isEqualTo(Status.SENT);

        var item = dynamo.getItem(r -> r.tableName(TABLE).key(Map.of("idempotencyKey", AttributeValue.fromS("key-1")))).item();
        assertThat(item.get("expiresAt").n()).isEqualTo(Long.toString(now.plus(Duration.ofDays(30)).getEpochSecond()));
        assertThat(item.get("destination").s()).isEqualTo("a***@example.com");
        assertThat(dynamo.describeTimeToLive(r -> r.tableName(TABLE)).timeToLiveDescription().attributeName())
                .isEqualTo("expiresAt");
    }

    @Test
    void aReleasedKeyCanBeReservedAgainAndAStalePendingOneTakenOverOnlyByTheSameRequest() {
        DynamoDbNotificationStore store = new DynamoDbNotificationStore(dynamo, TABLE);
        Instant now = Instant.parse("2026-11-21T11:00:00Z");
        assertThat(store.reserve(pending("key-2", "hash-2", "id-1", now), now)).isEmpty();
        store.release("key-2", "id-other"); // not ours: kept
        assertThat(store.reserve(pending("key-2", "hash-2", "id-2", now), now)).isPresent();
        store.release("key-2", "id-1");
        assertThat(store.reserve(pending("key-2", "hash-2", "id-2", now), now)).isEmpty();

        Instant later = now.plusSeconds(61);
        // An unknown outcome is kept as UNCERTAIN: never taken over, never released.
        assertThat(store.reserve(pending("key-3", "hash-3", "id-1", now), now)).isEmpty();
        store.markUncertain("key-3", "id-1");
        store.release("key-3", "id-1");
        assertThat(store.reserve(pending("key-3", "hash-3", "id-2", later), later).orElseThrow().status())
                .isEqualTo(Status.UNCERTAIN);

        assertThat(store.reserve(pending("key-2", "other-hash", "id-3", later), later).orElseThrow().requestHash())
                .isEqualTo("hash-2");
        assertThat(store.reserve(pending("key-2", "hash-2", "id-4", later), later)).isEmpty();
    }
}
