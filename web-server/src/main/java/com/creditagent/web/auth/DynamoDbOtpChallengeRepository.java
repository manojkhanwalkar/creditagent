package com.creditagent.web.auth;

import com.creditagent.web.auth.otp.OtpChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Login challenges table: partition key {@code challengeId} (string), TTL on {@code expiresAt} (epoch
 * seconds).
 *
 * <p>The key is a SHA-256 of the challenge id the browser holds, so someone who can read the table
 * cannot finish a sign-in with what they read. Per-account hourly send counters live in the same table
 * under {@code user#<sha-256 of the user id>#<hour>}; a hex digest never starts with {@code user#}, so
 * the two never collide. Both kinds of item expire through the TTL.
 */
public class DynamoDbOtpChallengeRepository implements OtpChallengeRepository {

    static final String KEY = "challengeId";
    static final String TTL_ATTRIBUTE = "expiresAt";
    static final String USER_COUNTER_PREFIX = "user#";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbOtpChallengeRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void create(OtpChallenge challenge) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, s(key(challenge.challengeId())));
        item.put("userId", s(challenge.userId()));
        item.put("createdAt", s(challenge.createdAt().toString()));
        item.put(TTL_ATTRIBUTE, n(challenge.expiresAt().getEpochSecond()));
        item.put("sends", n(challenge.sends()));
        item.put("attempts", n(challenge.attempts()));
        dynamo.putItem(r -> r.tableName(table).item(item));
    }

    @Override
    public Optional<OtpChallenge> find(String challengeId) {
        GetItemResponse response = dynamo.getItem(
                r -> r.tableName(table).key(Map.of(KEY, s(key(challengeId)))).consistentRead(true));
        if (!response.hasItem() || response.item().isEmpty() || !response.item().containsKey("userId")) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        String channel = DynamoDbUserRepository.text(item, "channel");
        return Optional.of(new OtpChallenge(
                challengeId,
                item.get("userId").s(),
                Instant.parse(item.get("createdAt").s()),
                Instant.ofEpochSecond(Long.parseLong(item.get(TTL_ATTRIBUTE).n())),
                channel == null ? null : OtpChannel.valueOf(channel),
                DynamoDbUserRepository.text(item, "codeHash"),
                epochMilli(item, "codeExpiresAt"),
                epochMilli(item, "lastSentAt"),
                Integer.parseInt(item.get("sends").n()),
                Integer.parseInt(item.get("attempts").n())));
    }

    @Override
    public boolean recordSend(String challengeId, OtpChannel channel, String codeHash, Instant now, Instant codeExpiresAt,
            Instant notSentSince, int maxSends) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(key(challengeId))))
                    .updateExpression("SET #channel = :channel, #hash = :hash, #codeExp = :codeExp, #last = :now ADD #sends :one")
                    .conditionExpression("attribute_exists(#id) AND #exp > :nowSec AND #sends < :max"
                            + " AND (attribute_not_exists(#last) OR #last <= :since)")
                    .expressionAttributeNames(Map.of("#id", KEY, "#channel", "channel", "#hash", "codeHash",
                            "#codeExp", "codeExpiresAt", "#last", "lastSentAt", "#sends", "sends", "#exp", TTL_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(
                            ":channel", s(channel.name()),
                            ":hash", s(codeHash),
                            ":codeExp", n(codeExpiresAt.toEpochMilli()),
                            ":now", n(now.toEpochMilli()),
                            ":nowSec", n(now.getEpochSecond()),
                            ":one", n(1),
                            ":max", n(maxSends),
                            ":since", n(notSentSince.toEpochMilli()))));
            return true;
        } catch (ConditionalCheckFailedException refused) {
            return false;
        }
    }

    @Override
    public boolean recordAttempt(String challengeId, int maxAttempts) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(key(challengeId))))
                    .updateExpression("ADD #attempts :one")
                    .conditionExpression("attribute_exists(#id) AND #attempts < :max")
                    .expressionAttributeNames(Map.of("#id", KEY, "#attempts", "attempts"))
                    .expressionAttributeValues(Map.of(":one", n(1), ":max", n(maxAttempts))));
            return true;
        } catch (ConditionalCheckFailedException refused) {
            return false;
        }
    }

    @Override
    public boolean consume(String challengeId, String codeHash) {
        try {
            dynamo.deleteItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(key(challengeId))))
                    .conditionExpression("#hash = :hash")
                    .expressionAttributeNames(Map.of("#hash", "codeHash"))
                    .expressionAttributeValues(Map.of(":hash", s(codeHash))));
            return true;
        } catch (ConditionalCheckFailedException refused) {
            return false;
        }
    }

    @Override
    public void delete(String challengeId) {
        dynamo.deleteItem(r -> r.tableName(table).key(Map.of(KEY, s(key(challengeId)))));
    }

    /** A fixed window per clock hour; the counter item lives one hour past its window. */
    @Override
    public boolean reserveUserSend(String userId, Instant now, int maxPerHour) {
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        String counter = userCounterKey(userId, now);
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(counter)))
                    .updateExpression("SET #exp = :exp ADD #sends :one")
                    .conditionExpression("attribute_not_exists(#sends) OR #sends < :max")
                    .expressionAttributeNames(Map.of("#sends", "sends", "#exp", TTL_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(
                            ":one", n(1),
                            ":max", n(maxPerHour),
                            ":exp", n(hour.plus(Duration.ofHours(2)).getEpochSecond()))));
            return true;
        } catch (ConditionalCheckFailedException limit) {
            return false;
        }
    }

    @Override
    public void releaseSend(String challengeId, String codeHash, OtpChallenge previous) {
        Map<String, String> names = new HashMap<>(Map.of("#channel", "channel", "#hash", "codeHash",
                "#codeExp", "codeExpiresAt", "#last", "lastSentAt", "#sends", "sends"));
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":hash", s(codeHash), ":minus", n(-1), ":zero", n(0)));
        String update;
        if (previous != null && previous.codeHash() != null) {
            update = "SET #channel = :pc, #hash = :ph, #codeExp = :pe, #last = :pl ADD #sends :minus";
            values.put(":pc", s(previous.channel().name()));
            values.put(":ph", s(previous.codeHash()));
            values.put(":pe", n(previous.codeExpiresAt().toEpochMilli()));
            values.put(":pl", n(previous.lastSentAt().toEpochMilli()));
        } else {
            update = "REMOVE #channel, #hash, #codeExp, #last ADD #sends :minus";
        }
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(key(challengeId))))
                    .updateExpression(update)
                    .conditionExpression("#hash = :hash AND #sends > :zero")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException changed) {
            // Gone, or another send replaced the code: nothing of ours to undo.
        }
    }

    @Override
    public void releaseUserSend(String userId, Instant now) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(userCounterKey(userId, now))))
                    .updateExpression("ADD #sends :minus")
                    .conditionExpression("#sends > :zero")
                    .expressionAttributeNames(Map.of("#sends", "sends"))
                    .expressionAttributeValues(Map.of(":minus", n(-1), ":zero", n(0))));
        } catch (ConditionalCheckFailedException none) {
            // Already at zero or expired.
        }
    }

    static String userCounterKey(String userId, Instant now) {
        return USER_COUNTER_PREFIX + sha256(userId) + "#" + now.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
    }

    /** The stored key of a challenge id. */
    static String key(String challengeId) {
        return sha256(challengeId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static Instant epochMilli(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
