package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.creditagent.web.auth.otp.OtpChannel;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** The challenge repository against a mocked DynamoDB client: the exact requests and conditions. */
class DynamoDbOtpChallengeRepositoryUnitTest {

    final DynamoDbClient dynamo = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
    final DynamoDbOtpChallengeRepository repo = new DynamoDbOtpChallengeRepository(dynamo, "challenges");

    static final Instant NOW = Instant.parse("2026-11-18T12:34:56Z");
    static final String ID = "browser-held-challenge-id";

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    @Test
    void createStoresTheChallengeUnderAHashOfItsIdWithATtl() {
        doReturn(null).when(dynamo).putItem(any(PutItemRequest.class));

        repo.create(OtpChallenge.opened(ID, "avery", NOW, NOW.plusSeconds(600)));

        ArgumentCaptor<PutItemRequest> sent = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(sent.capture());
        Map<String, AttributeValue> item = sent.getValue().item();
        assertThat(sent.getValue().tableName()).isEqualTo("challenges");
        assertThat(item.get("challengeId").s()).isEqualTo(DynamoDbOtpChallengeRepository.key(ID)).hasSize(64)
                .doesNotContain(ID);
        assertThat(item).containsEntry("userId", AttributeValue.fromS("avery"))
                .containsEntry("expiresAt", n(NOW.plusSeconds(600).getEpochSecond()))
                .containsEntry("sends", n(0)).containsEntry("attempts", n(0))
                .doesNotContainKeys("codeHash", "channel");
    }

    @Test
    void findReadsConsistentlyAndMapsEveryField() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("challengeId", AttributeValue.fromS(DynamoDbOtpChallengeRepository.key(ID)));
        item.put("userId", AttributeValue.fromS("avery"));
        item.put("createdAt", AttributeValue.fromS(NOW.toString()));
        item.put("expiresAt", n(NOW.plusSeconds(600).getEpochSecond()));
        item.put("channel", AttributeValue.fromS("SMS"));
        item.put("codeHash", AttributeValue.fromS("abc"));
        item.put("codeExpiresAt", n(NOW.plusSeconds(300).toEpochMilli()));
        item.put("lastSentAt", n(NOW.toEpochMilli()));
        item.put("sends", n(1));
        item.put("attempts", n(2));
        doReturn(GetItemResponse.builder().item(item).build()).when(dynamo).getItem(any(GetItemRequest.class));

        OtpChallenge found = repo.find(ID).orElseThrow();

        assertThat(found).isEqualTo(new OtpChallenge(ID, "avery", NOW, NOW.plusSeconds(600), OtpChannel.SMS, "abc",
                NOW.plusSeconds(300), NOW, 1, 2));
        ArgumentCaptor<GetItemRequest> sent = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo).getItem(sent.capture());
        assertThat(sent.getValue().consistentRead()).isTrue();
        assertThat(sent.getValue().key().get("challengeId").s()).isEqualTo(DynamoDbOtpChallengeRepository.key(ID));
    }

    @Test
    void findIgnoresMissingItemsAndCounterItems() {
        doReturn(GetItemResponse.builder().build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(repo.find(ID)).isEmpty();

        doReturn(GetItemResponse.builder().item(Map.of()).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(repo.find(ID)).isEmpty();

        doReturn(GetItemResponse.builder().item(Map.of("sends", n(3))).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(repo.find(ID)).isEmpty();
    }

    @Test
    void findMapsAChallengeWithNoCodeYet() {
        Map<String, AttributeValue> item = Map.of("userId", AttributeValue.fromS("avery"),
                "createdAt", AttributeValue.fromS(NOW.toString()), "expiresAt", n(NOW.getEpochSecond() + 600),
                "sends", n(0), "attempts", n(0));
        doReturn(GetItemResponse.builder().item(item).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(repo.find(ID).orElseThrow()).isEqualTo(OtpChallenge.opened(ID, "avery", NOW, NOW.plusSeconds(600)));
    }

    @Test
    void recordSendIsConditionalOnExpiryLimitAndCooldown() {
        doReturn(null).when(dynamo).updateItem(any(UpdateItemRequest.class));

        assertThat(repo.recordSend(ID, OtpChannel.EMAIL, "hash", NOW, NOW.plusSeconds(300), NOW.minusSeconds(30), 3)).isTrue();

        ArgumentCaptor<UpdateItemRequest> sent = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(sent.capture());
        UpdateItemRequest request = sent.getValue();
        assertThat(request.conditionExpression()).isEqualTo("attribute_exists(#id) AND #exp > :nowSec AND #sends < :max"
                + " AND (attribute_not_exists(#last) OR #last <= :since)");
        assertThat(request.updateExpression()).contains("ADD #sends :one").contains("#hash = :hash");
        assertThat(request.expressionAttributeValues()).containsEntry(":max", n(3))
                .containsEntry(":nowSec", n(NOW.getEpochSecond()))
                .containsEntry(":since", n(NOW.minusSeconds(30).toEpochMilli()))
                .containsEntry(":channel", AttributeValue.fromS("EMAIL"));

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(repo.recordSend(ID, OtpChannel.EMAIL, "hash", NOW, NOW.plusSeconds(300), NOW, 3)).isFalse();
    }

    @Test
    void recordAttemptIsConditionalOnTheLimit() {
        doReturn(null).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(repo.recordAttempt(ID, 5)).isTrue();
        ArgumentCaptor<UpdateItemRequest> sent = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).isEqualTo("attribute_exists(#id) AND #attempts < :max");
        assertThat(sent.getValue().expressionAttributeValues()).containsEntry(":max", n(5));

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(repo.recordAttempt(ID, 5)).isFalse();
    }

    @Test
    void consumeDeletesOnlyWhileTheCodeIsUnchanged() {
        doReturn(null).when(dynamo).deleteItem(any(DeleteItemRequest.class));
        assertThat(repo.consume(ID, "hash")).isTrue();
        ArgumentCaptor<DeleteItemRequest> sent = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).isEqualTo("#hash = :hash");
        assertThat(sent.getValue().expressionAttributeValues()).containsEntry(":hash", AttributeValue.fromS("hash"));

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).deleteItem(any(DeleteItemRequest.class));
        assertThat(repo.consume(ID, "hash")).isFalse();
    }

    @Test
    void deleteRemovesTheHashedKey() {
        doReturn(null).when(dynamo).deleteItem(any(DeleteItemRequest.class));
        repo.delete(ID);
        ArgumentCaptor<DeleteItemRequest> sent = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(sent.capture());
        assertThat(sent.getValue().key().get("challengeId").s()).isEqualTo(DynamoDbOtpChallengeRepository.key(ID));
        assertThat(sent.getValue().conditionExpression()).isNull();
    }

    @Test
    void userSendsAreCountedPerClockHourUnderAHashedUserId() {
        doReturn(null).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(repo.reserveUserSend("avery", NOW, 10)).isTrue();

        ArgumentCaptor<UpdateItemRequest> sent = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(sent.capture());
        UpdateItemRequest request = sent.getValue();
        long hour = Instant.parse("2026-11-18T12:00:00Z").getEpochSecond();
        assertThat(request.key().get("challengeId").s()).startsWith("user#").endsWith("#" + hour).doesNotContain("avery");
        assertThat(request.conditionExpression()).isEqualTo("attribute_not_exists(#sends) OR #sends < :max");
        assertThat(request.expressionAttributeValues()).containsEntry(":max", n(10)).containsEntry(":exp", n(hour + 7200));

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(repo.reserveUserSend("avery", NOW, 10)).isFalse();
    }
}
