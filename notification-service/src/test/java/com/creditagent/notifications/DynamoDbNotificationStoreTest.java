package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creditagent.notifications.NotificationStore.Notification;
import com.creditagent.notifications.NotificationStore.Status;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

/** The store's requests and its handling of failed conditions, on a mocked client (the real table: the integration test). */
class DynamoDbNotificationStoreTest {

    static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    static Notification record() {
        return new Notification("k".repeat(64), "h".repeat(64), "id-1", Channel.SMS, Template.OTP_SIGN_IN, "+1******0100",
                Status.PENDING, null, NOW, NOW.plusSeconds(60), NOW.plusSeconds(30L * 86400));
    }

    static Map<String, AttributeValue> item(Status status, String provider) {
        Map<String, AttributeValue> item = new java.util.HashMap<>(Map.of(
                "idempotencyKey", AttributeValue.fromS("k".repeat(64)), "requestHash", AttributeValue.fromS("h".repeat(64)),
                "id", AttributeValue.fromS("id-0"), "channel", AttributeValue.fromS("SMS"),
                "template", AttributeValue.fromS("OTP_SIGN_IN"), "destination", AttributeValue.fromS("+1******0100"),
                "status", AttributeValue.fromS(status.name()), "createdAt", AttributeValue.fromS(NOW.toString()),
                "leaseUntil", AttributeValue.fromN(Long.toString(NOW.plusSeconds(60).toEpochMilli())),
                "expiresAt", AttributeValue.fromN(Long.toString(NOW.plusSeconds(100).getEpochSecond()))));
        if (provider != null) {
            item.put("providerMessageId", AttributeValue.fromS(provider));
        }
        return item;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveIsAConditionalPutOfEveryFieldButNoSecret() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        assertThat(new DynamoDbNotificationStore(dynamo, "t").reserve(record(), NOW)).isEmpty();
        ArgumentCaptor<Consumer<PutItemRequest.Builder>> put = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamo).putItem(put.capture());
        PutItemRequest.Builder builder = PutItemRequest.builder();
        put.getValue().accept(builder);
        PutItemRequest request = builder.build();
        assertThat(request.conditionExpression()).startsWith("attribute_not_exists(#k)");
        assertThat(request.item()).containsOnlyKeys("idempotencyKey", "requestHash", "id", "channel", "template",
                "destination", "status", "createdAt", "leaseUntil", "expiresAt");
        assertThat(request.item().get("expiresAt").n()).isEqualTo(Long.toString(NOW.plusSeconds(30L * 86400).getEpochSecond()));
        assertThat(request.item().get("status").s()).isEqualTo("PENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHeldKeyComesBackFromTheFailedConditionOrAConsistentRead() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        when(dynamo.putItem(any(Consumer.class)))
                .thenThrow(ConditionalCheckFailedException.builder().item(item(Status.SENT, "p-1")).build())
                .thenThrow(ConditionalCheckFailedException.builder().build())
                .thenThrow(ConditionalCheckFailedException.builder().build());
        when(dynamo.getItem(any(Consumer.class)))
                .thenReturn(GetItemResponse.builder().item(item(Status.PENDING, null)).build())
                .thenReturn(GetItemResponse.builder().build());
        DynamoDbNotificationStore store = new DynamoDbNotificationStore(dynamo, "t");

        Notification sent = store.reserve(record(), NOW).orElseThrow();
        assertThat(sent.status()).isEqualTo(Status.SENT);
        assertThat(sent.id()).isEqualTo("id-0");
        assertThat(sent.providerMessageId()).isEqualTo("p-1");
        assertThat(sent.leaseUntil()).isEqualTo(NOW.plusSeconds(60));

        Notification pending = store.reserve(record(), NOW).orElseThrow();
        assertThat(pending.status()).isEqualTo(Status.PENDING);
        assertThat(pending.providerMessageId()).isNull();

        // Released between the put and the read: in progress, retry at once.
        Notification gone = store.reserve(record(), NOW).orElseThrow();
        assertThat(gone.status()).isEqualTo(Status.PENDING);
        assertThat(gone.leaseUntil()).isEqualTo(NOW);
        assertThat(DynamoDbNotificationStore.from(item(Status.SENT, "")).providerMessageId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void markSentAndReleaseTouchOnlyTheirOwnReservation() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        DynamoDbNotificationStore store = new DynamoDbNotificationStore(dynamo, "t");
        store.markSent("k", "id-1", null);
        store.release("k", "id-1");
        ArgumentCaptor<Consumer<UpdateItemRequest.Builder>> update = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamo).updateItem(update.capture());
        UpdateItemRequest.Builder u = UpdateItemRequest.builder();
        update.getValue().accept(u);
        assertThat(u.build().conditionExpression()).isEqualTo("#i = :id");
        assertThat(u.build().expressionAttributeValues().get(":pid").s()).isEmpty();
        ArgumentCaptor<Consumer<DeleteItemRequest.Builder>> delete = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamo).deleteItem(delete.capture());
        DeleteItemRequest.Builder d = DeleteItemRequest.builder();
        delete.getValue().accept(d);
        assertThat(d.build().conditionExpression()).isEqualTo("#i = :id AND #s = :pending");

        when(dynamo.updateItem(any(Consumer.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        when(dynamo.deleteItem(any(Consumer.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        store.markSent("k", "id-1", "p");
        store.release("k", "id-1"); // taken over by another request: nothing of ours to change
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureCreatesAMissingTableAndTurnsOnItsTtl() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        DynamoDbWaiter waiter = mock(DynamoDbWaiter.class);
        when(dynamo.waiter()).thenReturn(waiter);
        when(dynamo.describeTable(any(Consumer.class))).thenThrow(ResourceNotFoundException.builder().build());
        when(dynamo.describeTimeToLive(any(Consumer.class))).thenReturn(DescribeTimeToLiveResponse.builder().build());
        NotificationsTable.ensure(dynamo, "t");
        ArgumentCaptor<Consumer<CreateTableRequest.Builder>> create = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamo).createTable(create.capture());
        CreateTableRequest.Builder c = CreateTableRequest.builder();
        create.getValue().accept(c);
        assertThat(c.build().keySchema()).singleElement().satisfies(k -> assertThat(k.attributeName()).isEqualTo("idempotencyKey"));
        ArgumentCaptor<Consumer<UpdateTimeToLiveRequest.Builder>> ttl = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamo).updateTimeToLive(ttl.capture());
        UpdateTimeToLiveRequest.Builder t = UpdateTimeToLiveRequest.builder();
        ttl.getValue().accept(t);
        assertThat(t.build().timeToLiveSpecification().attributeName()).isEqualTo("expiresAt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureToleratesRacesAndAnExistingTable() {
        DynamoDbClient raced = mock(DynamoDbClient.class);
        when(raced.describeTable(any(Consumer.class))).thenThrow(ResourceNotFoundException.builder().build());
        when(raced.createTable(any(Consumer.class))).thenThrow(ResourceInUseException.builder().build());
        when(raced.describeTimeToLive(any(Consumer.class))).thenReturn(DescribeTimeToLiveResponse.builder()
                .timeToLiveDescription(d -> d.timeToLiveStatus(TimeToLiveStatus.DISABLED)).build());
        when(raced.updateTimeToLive(any(Consumer.class))).thenThrow(DynamoDbException.builder().message("already").build());
        NotificationsTable.ensure(raced, "t");

        DynamoDbClient ready = mock(DynamoDbClient.class);
        when(ready.describeTimeToLive(any(Consumer.class))).thenReturn(DescribeTimeToLiveResponse.builder()
                .timeToLiveDescription(d -> d.timeToLiveStatus(TimeToLiveStatus.ENABLED)).build());
        NotificationsTable.ensure(ready, "t");
        verify(ready, never()).createTable(any(Consumer.class));
        verify(ready, never()).updateTimeToLive(any(Consumer.class));

        DynamoDbClient enabling = mock(DynamoDbClient.class);
        when(enabling.describeTimeToLive(any(Consumer.class))).thenReturn(DescribeTimeToLiveResponse.builder()
                .timeToLiveDescription(d -> d.timeToLiveStatus(TimeToLiveStatus.ENABLING)).build());
        NotificationsTable.ensure(enabling, "t");
        verify(enabling, never()).updateTimeToLive(any(Consumer.class));
        assertThat(DescribeTableRequest.builder().build()).isNotNull();
        assertThat(GetItemRequest.builder().build()).isNotNull();
    }
}
