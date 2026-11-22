package com.creditagent.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;

class SubscriptionStoreTest {

    static final Instant START = Instant.parse("2026-11-21T10:00:00Z");
    static final Instant END = Instant.parse("2027-11-21T10:00:00Z");

    final DynamoDbClient dynamo = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
    final DynamoDbSubscriptionRepository store = new DynamoDbSubscriptionRepository(dynamo, "Subscriptions");

    @Test
    void productsParseInAnyCaseAndUnknownNamesAreEmpty() {
        assertThat(Product.parse(" id_protection ")).contains(Product.ID_PROTECTION);
        assertThat(Product.parse("CREDIT_MONITORING")).contains(Product.CREDIT_MONITORING);
        assertThat(Product.parse("gold")).isEmpty();
        assertThat(Product.parse(null)).isEmpty();
    }

    @Test
    void aSubscriptionIsActiveUntilItExpiresAndMustEndAfterItStarts() {
        Subscription s = new Subscription("ada", Product.ID_PROTECTION, START, END, "pay_1");
        assertThat(s.activeAt(START)).isTrue();
        assertThat(s.activeAt(END.minusMillis(1))).isTrue();
        assertThat(s.activeAt(END)).isFalse();
        assertThatThrownBy(() -> new Subscription("ada", Product.ID_PROTECTION, END, START, "pay_1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailKeysAreHashesOfTheTrimmedLowerCaseAddress() {
        String key = SubscriptionKeys.emailKey(" Ada@Example.COM ");
        assertThat(key).isEqualTo(SubscriptionKeys.emailKey("ada@example.com")).startsWith("email#").hasSize(6 + 64)
                .doesNotContain("ada");
    }

    @Test
    void activateWritesTheUserAndEmailItemsInOneConditionalTransaction() {
        doReturn(null).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));

        assertThat(store.activate(new Subscription("ada", Product.ID_PROTECTION, START, END, "pay_1"), "ada@example.com")).isTrue();

        ArgumentCaptor<TransactWriteItemsRequest> sent = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(sent.capture());
        var items = sent.getValue().transactItems();
        assertThat(items).hasSize(2);
        var user = items.get(0).put();
        assertThat(user.item().get("pk").s()).isEqualTo("ada");
        assertThat(user.item().get("product").s()).isEqualTo("ID_PROTECTION");
        assertThat(user.item().get("expiresAtMillis").n()).isEqualTo(Long.toString(END.toEpochMilli()));
        assertThat(user.conditionExpression()).isEqualTo("attribute_not_exists(#pk) OR #expires <= :start");
        assertThat(user.expressionAttributeValues().get(":start").n()).isEqualTo(Long.toString(START.toEpochMilli()));
        var email = items.get(1).put();
        assertThat(email.item().get("pk").s()).isEqualTo(SubscriptionKeys.emailKey("ada@example.com"));
        assertThat(email.item().get("owner").s()).isEqualTo("ada");
        assertThat(email.item()).doesNotContainKey("paymentReference");
    }

    @Test
    void activateReturnsFalseWhenAlreadyActiveAndRethrowsOtherCancellations() {
        doThrow(TransactionCanceledException.builder().cancellationReasons(
                CancellationReason.builder().code("ConditionalCheckFailed").build(),
                CancellationReason.builder().code("None").build()).build())
                .when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        Subscription s = new Subscription("ada", Product.ID_PROTECTION, START, END, "pay_1");
        assertThat(store.activate(s, "ada@example.com")).isFalse();

        doThrow(TransactionCanceledException.builder().cancellationReasons(
                CancellationReason.builder().code("TransactionConflict").build()).build())
                .when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThatThrownBy(() -> store.activate(s, "ada@example.com")).isInstanceOf(TransactionCanceledException.class);

        doThrow(TransactionCanceledException.builder().build())
                .when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThatThrownBy(() -> store.activate(s, "ada@example.com")).isInstanceOf(TransactionCanceledException.class);
    }

    @Test
    void userIdsThatCouldNameAnEmailItemAreRefused() {
        assertThatThrownBy(() -> store.findAll("email#abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findAll(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.eraseAll(null, "a@b.co")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findAllReadsTheUsersItemsAndSkipsUnknownProducts() {
        doReturn(QueryResponse.builder().items(
                Map.of("pk", AttributeValue.fromS("ada"), "product", AttributeValue.fromS("ID_PROTECTION"),
                        "startedAt", AttributeValue.fromS(START.toString()), "expiresAt", AttributeValue.fromS(END.toString()),
                        "paymentReference", AttributeValue.fromS("pay_1")),
                Map.of("pk", AttributeValue.fromS("ada"), "product", AttributeValue.fromS("RETIRED_PRODUCT"))).build())
                .when(dynamo).query(any(QueryRequest.class));

        assertThat(store.findAll("ada")).containsExactly(new Subscription("ada", Product.ID_PROTECTION, START, END, "pay_1"));
        ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(sent.capture());
        assertThat(sent.getValue().consistentRead()).isTrue();
    }

    @Test
    void activeForEmailComparesTheExpiryWithNow() {
        Map<String, AttributeValue> item = Map.of("expiresAtMillis", AttributeValue.fromN(Long.toString(END.toEpochMilli())));
        doReturn(GetItemResponse.builder().item(item).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(store.activeForEmail("Ada@example.com", Product.ID_PROTECTION, START)).isTrue();
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, END)).isFalse();

        ArgumentCaptor<GetItemRequest> sent = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo, times(2)).getItem(sent.capture());
        assertThat(sent.getValue().key().get("pk").s()).isEqualTo(SubscriptionKeys.emailKey("ada@example.com"));

        doReturn(GetItemResponse.builder().build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, START)).isFalse();
        doReturn(GetItemResponse.builder().item(Map.of()).build()).when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, START)).isFalse();
        doReturn(GetItemResponse.builder().item(Map.of("other", AttributeValue.fromS("x"))).build())
                .when(dynamo).getItem(any(GetItemRequest.class));
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, START)).isFalse();
    }

    @Test
    void eraseAllDeletesEveryProductForTheUserAndTheEmail() {
        doReturn(null).when(dynamo).deleteItem(any(DeleteItemRequest.class));
        store.eraseAll("ada", "ada@example.com");
        ArgumentCaptor<DeleteItemRequest> sent = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo, times(2 * Product.values().length)).deleteItem(sent.capture());
        List<String> keys = sent.getAllValues().stream().map(d -> d.key().get("pk").s() + "/" + d.key().get("product").s()).toList();
        assertThat(keys).contains("ada/ID_PROTECTION", "ada/CREDIT_MONITORING",
                SubscriptionKeys.emailKey("ada@example.com") + "/ID_PROTECTION");
    }

    @Test
    void eraseAllWithoutAnEmailDeletesOnlyTheUsersItems() {
        doReturn(null).when(dynamo).deleteItem(any(DeleteItemRequest.class));
        store.eraseAll("ada", null);
        verify(dynamo, times(Product.values().length)).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void ensureCreatesTheTableOnlyWhenMissing() {
        doReturn(DescribeTableResponse.builder().build()).when(dynamo).describeTable(any(DescribeTableRequest.class));
        assertThat(SubscriptionsTable.ensure(dynamo, "Subscriptions")).isFalse();

        doThrow(ResourceNotFoundException.builder().build()).when(dynamo).describeTable(any(DescribeTableRequest.class));
        doThrow(ResourceInUseException.builder().build()).when(dynamo).createTable(any(CreateTableRequest.class));
        assertThat(SubscriptionsTable.ensure(dynamo, "Subscriptions")).isFalse();
    }
}
