package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.web.auth.otp.OtpChannel;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** The challenge repository's conditional writes against DynamoDB Local. Skipped where Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbOtpChallengeRepositoryTest {

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(DynamoDbRepositoriesTest.IMAGE)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static DynamoDbClient client;
    static DynamoDbOtpChallengeRepository repo;

    /** An hour ahead of the real clock, so DynamoDB Local's TTL never removes what a test wrote. */
    static final Instant NOW = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);

    @BeforeAll
    static void connect() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        DynamoDbTables.ensureWithTtl(client, "challenges", DynamoDbOtpChallengeRepository.KEY,
                DynamoDbOtpChallengeRepository.TTL_ATTRIBUTE);
        DynamoDbTables.ensureWithTtl(client, "challenges", DynamoDbOtpChallengeRepository.KEY,
                DynamoDbOtpChallengeRepository.TTL_ATTRIBUTE); // idempotent
        repo = new DynamoDbOtpChallengeRepository(client, "challenges");
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void aChallengeGoesThroughSendAttemptAndConsume() {
        repo.create(OtpChallenge.opened("c-1", "avery", NOW, NOW.plusSeconds(600)));
        assertThat(repo.find("c-1")).contains(OtpChallenge.opened("c-1", "avery", NOW, NOW.plusSeconds(600)));

        assertThat(repo.recordSend("c-1", OtpChannel.SMS, "h1", NOW, NOW.plusSeconds(300), NOW.minusSeconds(30), 2)).isTrue();
        // Within the cooldown: refused.
        Instant soon = NOW.plusSeconds(10);
        assertThat(repo.recordSend("c-1", OtpChannel.EMAIL, "h2", soon, soon.plusSeconds(300), soon.minusSeconds(30), 2)).isFalse();
        // Exactly at the cooldown: allowed. Then the send limit refuses a third.
        Instant later = NOW.plusSeconds(30);
        assertThat(repo.recordSend("c-1", OtpChannel.EMAIL, "h2", later, later.plusSeconds(300), later.minusSeconds(30), 2)).isTrue();
        Instant last = NOW.plusSeconds(90);
        assertThat(repo.recordSend("c-1", OtpChannel.EMAIL, "h3", last, last.plusSeconds(300), last.minusSeconds(30), 2)).isFalse();

        OtpChallenge stored = repo.find("c-1").orElseThrow();
        assertThat(stored.channel()).isEqualTo(OtpChannel.EMAIL);
        assertThat(stored.codeHash()).isEqualTo("h2");
        assertThat(stored.sends()).isEqualTo(2);
        assertThat(stored.lastSentAt()).isEqualTo(later);
        assertThat(stored.codeExpiresAt()).isEqualTo(later.plusSeconds(300));

        assertThat(repo.recordAttempt("c-1", 2)).isTrue();
        assertThat(repo.recordAttempt("c-1", 2)).isTrue();
        assertThat(repo.recordAttempt("c-1", 2)).isFalse();
        assertThat(repo.find("c-1").orElseThrow().attempts()).isEqualTo(2);

        assertThat(repo.consume("c-1", "h1")).isFalse(); // replaced by the resend
        assertThat(repo.consume("c-1", "h2")).isTrue();
        assertThat(repo.consume("c-1", "h2")).isFalse(); // once only
        assertThat(repo.find("c-1")).isEmpty();
        assertThat(repo.recordAttempt("c-1", 5)).isFalse(); // never recreated by an update
        assertThat(repo.find("c-1")).isEmpty();
    }

    @Test
    void anExpiredChallengeSendsNothing() {
        repo.create(OtpChallenge.opened("c-2", "avery", NOW, NOW.plusSeconds(60)));
        Instant after = NOW.plusSeconds(60);
        assertThat(repo.recordSend("c-2", OtpChannel.EMAIL, "h", after, after.plusSeconds(300), after, 3)).isFalse();
        assertThat(repo.recordSend("missing", OtpChannel.EMAIL, "h", NOW, NOW.plusSeconds(300), NOW, 3)).isFalse();
        assertThat(repo.find("missing")).isEmpty();
        repo.delete("c-2");
        assertThat(repo.find("c-2")).isEmpty();
    }

    @Test
    void anUndeliveredSendIsGivenBack() {
        repo.create(OtpChallenge.opened("c-3", "avery", NOW, NOW.plusSeconds(600)));
        assertThat(repo.recordSend("c-3", OtpChannel.SMS, "h1", NOW, NOW.plusSeconds(300), NOW.minusSeconds(30), 3)).isTrue();
        repo.releaseSend("c-3", "other"); // not the current code: untouched
        assertThat(repo.find("c-3").orElseThrow().sends()).isEqualTo(1);
        repo.releaseSend("c-3", "h1");
        assertThat(repo.find("c-3").orElseThrow()).isEqualTo(OtpChallenge.opened("c-3", "avery", NOW, NOW.plusSeconds(600)));
        // No cooldown left: a new send works at once.
        assertThat(repo.recordSend("c-3", OtpChannel.EMAIL, "h2", NOW, NOW.plusSeconds(300), NOW.minusSeconds(30), 3)).isTrue();
        repo.releaseSend("missing", "h");

        assertThat(repo.reserveUserSend("dana", NOW, 1)).isTrue();
        assertThat(repo.reserveUserSend("dana", NOW, 1)).isFalse();
        repo.releaseUserSend("dana", NOW);
        assertThat(repo.reserveUserSend("dana", NOW, 1)).isTrue();
        repo.releaseUserSend("nobody", NOW); // no counter: no error, nothing created below zero
    }

    @Test
    void anAccountsHourlySendsAreCapped() {
        assertThat(repo.reserveUserSend("blake", NOW, 2)).isTrue();
        assertThat(repo.reserveUserSend("blake", NOW, 2)).isTrue();
        assertThat(repo.reserveUserSend("blake", NOW, 2)).isFalse();
        assertThat(repo.reserveUserSend("casey", NOW, 2)).isTrue();
        assertThat(repo.reserveUserSend("blake", NOW.plus(Duration.ofHours(1)), 2)).isTrue();
    }
}
