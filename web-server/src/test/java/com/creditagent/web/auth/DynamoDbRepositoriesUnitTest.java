package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

/**
 * The DynamoDB repositories against a mocked client: the exact requests they send, and how they read
 * answers and failures. {@link DynamoDbRepositoriesTest} runs the same code against DynamoDB Local.
 */
class DynamoDbRepositoriesUnitTest {

    /** The SDK's consumer overloads build the request and call the request-object methods stubbed below. */
    final DynamoDbClient dynamo = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
    final DynamoDbUserRepository users = new DynamoDbUserRepository(dynamo, "users");
    final DynamoDbSessionRepository sessions = new DynamoDbSessionRepository(dynamo, "sessions");

    static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    static User user(String id) {
        return new User(id, "$2a$hash", id + "@example.com", "+14155550100", "hash-" + id, "6789", "kyc_" + id, NOW,
                ReportStatus.LOCKED);
    }

    static Map<String, AttributeValue> item(String... pairs) {
        Map<String, AttributeValue> item = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            item.put(pairs[i], AttributeValue.fromS(pairs[i + 1]));
        }
        return item;
    }

    static Map<String, AttributeValue> userItem(String id) {
        return item("userId", id, "passwordHash", "$2a$hash", "email", id + "@example.com", "phone", "+14155550100",
                "ssnHash", "hash-" + id, "ssnLastFour", "6789", "createdAt", NOW.toString());
    }

    static TransactionCanceledException cancelled(String first, String second) {
        return TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code(first).build(),
                        CancellationReason.builder().code(second).build())
                .build();
    }

    // ---------------------------------------------------------------- users

    @Test
    void createWritesTheUserAndTheSsnClaimInOneConditionalTransaction() {
        doReturn(null).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));

        assertThat(users.create(user("avery"))).isEqualTo(UserRepository.CreateResult.CREATED);

        ArgumentCaptor<TransactWriteItemsRequest> sent = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(sent.capture());
        var puts = sent.getValue().transactItems();
        assertThat(puts).hasSize(2);
        assertThat(puts.get(0).put().item()).containsEntry("userId", AttributeValue.fromS("avery"))
                .containsEntry("reportStatus", AttributeValue.fromS("LOCKED"))
                .containsEntry("kycVerificationId", AttributeValue.fromS("kyc_avery"));
        assertThat(puts.get(1).put().item()).containsEntry("userId", AttributeValue.fromS("ssn#hash-avery"))
                .containsEntry("owner", AttributeValue.fromS("avery"));
        assertThat(puts).allSatisfy(p -> assertThat(p.put().conditionExpression()).isEqualTo("attribute_not_exists(#id)"));
    }

    @Test
    void aUserWithoutAKycIdIsStoredWithoutTheAttribute() {
        doReturn(null).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        User legacy = new User("old", "$2a$hash", "o@example.com", "+14155550100", "hash-old", "6789", null, NOW, null);
        users.create(legacy);
        ArgumentCaptor<TransactWriteItemsRequest> sent = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(sent.capture());
        assertThat(sent.getValue().transactItems().get(0).put().item()).doesNotContainKey("kycVerificationId");
    }

    @Test
    void aCancelledTransactionSaysWhichItemWasTaken() {
        doThrow(cancelled("ConditionalCheckFailed", "None")).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThat(users.create(user("avery"))).isEqualTo(UserRepository.CreateResult.USER_ID_TAKEN);

        doThrow(cancelled("None", "ConditionalCheckFailed")).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThat(users.create(user("avery"))).isEqualTo(UserRepository.CreateResult.SSN_TAKEN);
    }

    @Test
    void anyOtherCancellationIsRethrown() {
        doThrow(cancelled("ThrottlingError", "None")).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThatThrownBy(() -> users.create(user("avery"))).isInstanceOf(TransactionCanceledException.class);

        doThrow(TransactionCanceledException.builder().build()).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThatThrownBy(() -> users.create(user("avery"))).isInstanceOf(TransactionCanceledException.class);
    }

    @Test
    void findReadsConsistentlyAndMapsEveryField() {
        doReturn(GetItemResponse.builder().item(userItem("avery")).build()).when(dynamo).getItem(any(GetItemRequest.class));

        User found = users.find("avery").orElseThrow();

        assertThat(found.email()).isEqualTo("avery@example.com");
        assertThat(found.ssnHash()).isEqualTo("hash-avery");
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(found.kycVerificationId()).isNull();
        assertThat(found.reportStatus()).isEqualTo(ReportStatus.UNLOCKED); // no attribute: an older row
        ArgumentCaptor<GetItemRequest> sent = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo).getItem(sent.capture());
        assertThat(sent.getValue().consistentRead()).isTrue();
        assertThat(sent.getValue().key()).containsEntry("userId", AttributeValue.fromS("avery"));
    }

    @Test
    void findReturnsEmptyForMissingItemsAndNeverReadsClaimKeys() {
        doReturn(GetItemResponse.builder().build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(users.find("nobody")).isEmpty();
        doReturn(GetItemResponse.builder().item(Map.of()).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(users.find("nobody")).isEmpty();

        assertThat(users.find("ssn#hash")).isEmpty();
        assertThat(users.find(null)).isEmpty();
        verify(dynamo, times(2)).getItem(any(GetItemRequest.class));
    }

    @Test
    void findBySsnHashFollowsTheClaimToItsOwner() {
        doReturn(GetItemResponse.builder().item(item("userId", "ssn#hash-avery", "owner", "avery")).build(),
                GetItemResponse.builder().item(userItem("avery")).build())
                .when(dynamo).getItem(any(GetItemRequest.class));

        assertThat(users.findBySsnHash("hash-avery")).hasValueSatisfying(u -> assertThat(u.userId()).isEqualTo("avery"));

        ArgumentCaptor<GetItemRequest> sent = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo, times(2)).getItem(sent.capture());
        assertThat(sent.getAllValues().get(0).key()).containsEntry("userId", AttributeValue.fromS("ssn#hash-avery"));
    }

    @Test
    void findBySsnHashIsEmptyWithoutAClaimOrAnOwner() {
        doReturn(GetItemResponse.builder().build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(users.findBySsnHash("hash-x")).isEmpty();

        doReturn(GetItemResponse.builder().item(item("userId", "ssn#hash-x")).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(users.findBySsnHash("hash-x")).isEmpty();

        assertThat(users.findBySsnHash("")).isEmpty();
        assertThat(users.findBySsnHash(null)).isEmpty();
    }

    @Test
    void updateReportStatusIsConditionalOnAnExistingUserItem() {
        doReturn(null).when(dynamo).updateItem(any(UpdateItemRequest.class));

        assertThat(users.updateReportStatus("avery", ReportStatus.LOCKED)).isTrue();

        ArgumentCaptor<UpdateItemRequest> sent = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).isEqualTo("attribute_exists(#id) AND attribute_exists(#pw)");
        assertThat(sent.getValue().expressionAttributeValues()).containsEntry(":status", AttributeValue.fromS("LOCKED"));
    }

    @Test
    void updateReportStatusRefusesMissingUsersAndClaimKeys() {
        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(users.updateReportStatus("ghost", ReportStatus.LOCKED)).isFalse();

        assertThat(users.updateReportStatus("ssn#hash", ReportStatus.LOCKED)).isFalse();
        assertThat(users.updateReportStatus(null, ReportStatus.LOCKED)).isFalse();
        verify(dynamo, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void zipAndLastScoreRoundTripAndTheScoreUpdateIsConditional() {
        doReturn(null).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        users.create(new User("zed", "$2a$hash", "z@example.com", "+14155550100", "hash-zed", "6789", "kyc", NOW,
                ReportStatus.UNLOCKED, "98101", null, null));
        ArgumentCaptor<TransactWriteItemsRequest> created = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(created.capture());
        assertThat(created.getValue().transactItems().getFirst().put().item()).containsEntry("zip", AttributeValue.fromS("98101"));

        Map<String, AttributeValue> stored = userItem("zed");
        stored.put("zip", AttributeValue.fromS("98101"));
        stored.put("lastScore", AttributeValue.fromN("731"));
        stored.put("lastScoreAt", AttributeValue.fromS(NOW.toString()));
        doReturn(GetItemResponse.builder().item(stored).build()).when(dynamo).getItem(any(GetItemRequest.class));
        User found = users.find("zed").orElseThrow();
        assertThat(found.zip()).isEqualTo("98101");
        assertThat(found.lastScore()).isEqualTo(731);
        assertThat(found.lastScoreAt()).isEqualTo(NOW);

        doReturn(null).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(users.updateLastScore("zed", 700, NOW)).isTrue();
        ArgumentCaptor<UpdateItemRequest> sent = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).contains("attribute_exists(#pw)");
        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(users.updateLastScore("gone", 700, NOW)).isFalse();
        assertThat(users.updateLastScore("ssn#x", 700, NOW)).isFalse();
        assertThat(users.updateLastScore(null, 700, NOW)).isFalse();
    }

    // ---------------------------------------------------------------- sessions

    @Test
    void saveWritesTheSessionWithItsTtl() {
        doReturn(null).when(dynamo).putItem(any(PutItemRequest.class));
        sessions.save(new Session("s1", "avery", "hash-a", "6789", NOW, NOW, NOW.plusSeconds(300)));
        sessions.save(new Session("s2", "avery", null, null, NOW, NOW, NOW.plusSeconds(300)));

        ArgumentCaptor<PutItemRequest> sent = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo, times(2)).putItem(sent.capture());
        assertThat(sent.getAllValues().get(0).item())
                .containsEntry("expiresAt", AttributeValue.fromN(Long.toString(NOW.plusSeconds(300).getEpochSecond())))
                .containsEntry("ssnHash", AttributeValue.fromS("hash-a"));
        assertThat(sent.getAllValues().get(1).item()).doesNotContainKeys("ssnHash", "ssnLastFour");
    }

    @Test
    void findMapsASessionOrNothing() {
        Map<String, AttributeValue> stored = item("sessionId", "s1", "userId", "avery", "ssnHash", "hash-a",
                "ssnLastFour", "6789", "createdAt", NOW.toString(), "lastActivityAt", NOW.toString());
        stored.put("expiresAt", AttributeValue.fromN(Long.toString(NOW.plusSeconds(300).getEpochSecond())));
        doReturn(GetItemResponse.builder().item(stored).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.find("s1")).hasValue(new Session("s1", "avery", "hash-a", "6789", NOW, NOW, NOW.plusSeconds(300)));

        doReturn(GetItemResponse.builder().build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.find("gone")).isEmpty();
        doReturn(GetItemResponse.builder().item(Map.of()).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.find("gone")).isEmpty();
    }

    @Test
    void createWritesOnlyANewSession() {
        doReturn(null).when(dynamo).putItem(any(PutItemRequest.class));
        Session grant = new Session("g1", "avery", "hash-a", "6789", NOW, NOW, NOW.plusSeconds(3600));
        assertThat(sessions.create(grant)).isTrue();
        ArgumentCaptor<PutItemRequest> sent = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).isEqualTo("attribute_not_exists(#id)");
        assertThat(sent.getValue().item()).containsEntry("sessionId", AttributeValue.fromS("g1"));

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).putItem(any(PutItemRequest.class));
        // Taken by someone else: nothing there to compare (deleted since), or another write's row.
        doReturn(GetItemResponse.builder().build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.create(grant)).isFalse();
        Map<String, AttributeValue> other = sessionItem("g1", "avery", NOW.minusSeconds(5));
        doReturn(GetItemResponse.builder().item(other).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.create(grant)).isFalse();
        doReturn(GetItemResponse.builder().item(sessionItem("g1", "mallory", NOW)).build())
                .when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.create(grant)).isFalse();
    }

    @Test
    void aRetriedCreateThatFindsItsOwnEarlierWriteSucceeds() {
        // The SDK retried a put whose first attempt timed out but had been stored.
        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).putItem(any(PutItemRequest.class));
        doReturn(GetItemResponse.builder().item(sessionItem("g1", "avery", NOW)).build())
                .when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(sessions.create(new Session("g1", "avery", "hash-a", "6789", NOW, NOW, NOW.plusSeconds(3600)))).isTrue();
    }

    static Map<String, AttributeValue> sessionItem(String id, String userId, Instant createdAt) {
        Map<String, AttributeValue> item = item("sessionId", id, "userId", userId, "ssnHash", "hash-a", "ssnLastFour", "6789",
                "createdAt", createdAt.toString(), "lastActivityAt", createdAt.toString());
        item.put("expiresAt", AttributeValue.fromN(Long.toString(NOW.plusSeconds(3600).getEpochSecond())));
        return item;
    }

    @Test
    void touchUpdatesOnlyAnExistingSession() {
        doReturn(null).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(sessions.touch("s1", NOW, NOW.plusSeconds(300))).isTrue();
        ArgumentCaptor<UpdateItemRequest> sent = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).isEqualTo("attribute_exists(#id)");

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).updateItem(any(UpdateItemRequest.class));
        assertThat(sessions.touch("gone", NOW, NOW.plusSeconds(300))).isFalse();
    }

    @Test
    void deleteRemovesTheSession() {
        doReturn(null).when(dynamo).deleteItem(any(DeleteItemRequest.class));
        sessions.delete("s1");
        ArgumentCaptor<DeleteItemRequest> sent = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(sent.capture());
        assertThat(sent.getValue().key()).containsEntry("sessionId", AttributeValue.fromS("s1"));
    }

    @Test
    void deleteExpiredPagesThroughTheScanAndBatchesTheDeletes() {
        List<Map<String, AttributeValue>> firstPage = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            firstPage.add(Map.of("sessionId", AttributeValue.fromS("s" + i)));
        }
        doReturn(ScanResponse.builder().items(firstPage).lastEvaluatedKey(Map.of("sessionId", AttributeValue.fromS("s29"))).build(),
                ScanResponse.builder().items(List.of(Map.of("sessionId", AttributeValue.fromS("s30")))).build())
                .when(dynamo).scan(any(ScanRequest.class));
        // The first batch comes back with one unprocessed delete, which is retried.
        WriteRequest leftOver = WriteRequest.builder().deleteRequest(d -> d.key(Map.of("sessionId", AttributeValue.fromS("s0")))).build();
        doReturn(BatchWriteItemResponse.builder().unprocessedItems(Map.of("sessions", List.of(leftOver))).build(),
                BatchWriteItemResponse.builder().build())
                .when(dynamo).batchWriteItem(any(BatchWriteItemRequest.class));

        assertThat(sessions.deleteExpired(NOW)).isEqualTo(31);

        ArgumentCaptor<ScanRequest> scans = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo, times(2)).scan(scans.capture());
        assertThat(scans.getAllValues().get(1).exclusiveStartKey()).containsEntry("sessionId", AttributeValue.fromS("s29"));
        ArgumentCaptor<BatchWriteItemRequest> batches = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamo, times(4)).batchWriteItem(batches.capture()); // 25, its retry, 5, then 1 from page two
        assertThat(batches.getAllValues().get(0).requestItems().get("sessions")).hasSize(25);
        assertThat(batches.getAllValues().get(1).requestItems().get("sessions")).containsExactly(leftOver);
    }

    @Test
    void deletesStillUnprocessedAfterThreeTriesAreLeftForTheNextSweep() {
        doReturn(ScanResponse.builder().items(List.of(Map.of("sessionId", AttributeValue.fromS("s1")))).build())
                .when(dynamo).scan(any(ScanRequest.class));
        WriteRequest stuck = WriteRequest.builder().deleteRequest(d -> d.key(Map.of("sessionId", AttributeValue.fromS("s1")))).build();
        doReturn(BatchWriteItemResponse.builder().unprocessedItems(Map.of("sessions", List.of(stuck))).build())
                .when(dynamo).batchWriteItem(any(BatchWriteItemRequest.class));

        assertThat(sessions.deleteExpired(NOW)).isEqualTo(1);
        verify(dynamo, times(3)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    // ---------------------------------------------------------------- tables

    @Test
    void existingTablesAreLeftAlone() {
        doReturn(DescribeTableResponse.builder().build()).when(dynamo).describeTable(any(DescribeTableRequest.class));
        DynamoDbTables.ensure(dynamo, "users", "sessions");
        verify(dynamo, never()).createTable(any(CreateTableRequest.class));
        verify(dynamo, never()).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
    }

    @Test
    void missingTablesAreCreatedAndSessionsGetATtl() {
        doThrow(ResourceNotFoundException.builder().build()).when(dynamo).describeTable(any(DescribeTableRequest.class));
        doReturn(null).when(dynamo).createTable(any(CreateTableRequest.class));
        doReturn(null).when(dynamo).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
        DynamoDbWaiter waiter = mock(DynamoDbWaiter.class, CALLS_REAL_METHODS);
        @SuppressWarnings("unchecked")
        WaiterResponse<DescribeTableResponse> waited = mock(WaiterResponse.class);
        doReturn(waited).when(waiter).waitUntilTableExists(any(DescribeTableRequest.class));
        doReturn(waiter).when(dynamo).waiter();

        DynamoDbTables.ensure(dynamo, "users", "sessions");

        ArgumentCaptor<CreateTableRequest> created = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(dynamo, times(2)).createTable(created.capture());
        assertThat(created.getAllValues()).extracting(CreateTableRequest::tableName).containsExactly("users", "sessions");
        ArgumentCaptor<DescribeTableRequest> waitedFor = ArgumentCaptor.forClass(DescribeTableRequest.class);
        verify(waiter, times(2)).waitUntilTableExists(waitedFor.capture());
        assertThat(waitedFor.getAllValues()).extracting(DescribeTableRequest::tableName).containsExactly("users", "sessions");
        ArgumentCaptor<UpdateTimeToLiveRequest> ttl = ArgumentCaptor.forClass(UpdateTimeToLiveRequest.class);
        verify(dynamo).updateTimeToLive(ttl.capture());
        assertThat(ttl.getValue().tableName()).isEqualTo("sessions");
        assertThat(ttl.getValue().timeToLiveSpecification().attributeName()).isEqualTo("expiresAt");
    }

    @Test
    void aTableCreatedConcurrentlyIsNotAnError() {
        doThrow(ResourceNotFoundException.builder().build()).when(dynamo).describeTable(any(DescribeTableRequest.class));
        doThrow(ResourceInUseException.builder().build()).when(dynamo).createTable(any(CreateTableRequest.class));

        DynamoDbTables.ensure(dynamo, "users", "sessions");

        verify(dynamo, never()).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
    }
}
