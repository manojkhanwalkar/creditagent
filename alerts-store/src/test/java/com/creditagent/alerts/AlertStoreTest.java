package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class AlertStoreTest {

    static final Instant T1 = Instant.parse("2026-11-09T12:00:00Z");

    final DynamoDbClient dynamo = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
    final DynamoDbAlertRepository alerts = new DynamoDbAlertRepository(dynamo, "Alerts");

    @Test
    void emailsAreNormalisedAndNeverPrinted() {
        Alert alert = new Alert("  Ada@Example.COM ", "paste-site-alpha", T1);
        assertThat(alert.email()).isEqualTo("ada@example.com");
        assertThat(alert.toString()).doesNotContain("ada").contains("paste-site-alpha");
    }

    @Test
    void recordIsAConditionalPutSoEachFindingIsStoredOnce() {
        doReturn(null).when(dynamo).putItem(any(PutItemRequest.class));
        assertThat(alerts.record(new Alert("ada@example.com", "loc", T1))).isTrue();
        ArgumentCaptor<PutItemRequest> sent = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(sent.capture());
        assertThat(sent.getValue().conditionExpression()).isEqualTo("attribute_not_exists(#email)");
        assertThat(sent.getValue().item()).containsEntry("email", AttributeValue.fromS("ada@example.com"))
                .containsEntry("location", AttributeValue.fromS("loc"))
                .containsEntry("timestamp", AttributeValue.fromS(T1.toString()));

        doThrow(ConditionalCheckFailedException.builder().build()).when(dynamo).putItem(any(PutItemRequest.class));
        assertThat(alerts.record(new Alert("ada@example.com", "loc", T1))).isFalse();
    }

    @Test
    void findQueriesTheNormalisedEmailOldestFirst() {
        Map<String, AttributeValue> late = Map.of("email", AttributeValue.fromS("ada@example.com"),
                "location", AttributeValue.fromS("b"), "timestamp", AttributeValue.fromS(T1.plusSeconds(60).toString()));
        Map<String, AttributeValue> early = Map.of("email", AttributeValue.fromS("ada@example.com"),
                "location", AttributeValue.fromS("a"), "timestamp", AttributeValue.fromS(T1.toString()));
        doReturn(QueryResponse.builder().items(late, early).build()).when(dynamo).query(any(QueryRequest.class));
        assertThat(alerts.find(" ADA@example.com")).extracting(Alert::location).containsExactly("a", "b");
        ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(sent.capture());
        assertThat(sent.getValue().expressionAttributeValues()).containsEntry(":email", AttributeValue.fromS("ada@example.com"));
        assertThat(sent.getValue().limit()).isEqualTo(DynamoDbAlertRepository.MAX_ALERTS);
    }

    @Test
    void theClientHasBoundedCalls() {
        try (DynamoDbClient client = AlertsTable.client("http://localhost:1", "us-east-1", Duration.ofSeconds(2))) {
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(Duration.ofSeconds(2));
        }
        try (DynamoDbClient client = AlertsTable.client("", "us-east-1", Duration.ofSeconds(2))) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void anEraseThatCannotDeleteEverythingFailsSoTheCallerRetries() {
        var row = java.util.Map.of("email", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("a@example.com"),
                "location", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("loc-a"));
        doReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder().items(row).build())
                .when(dynamo).query(any(QueryRequest.class));
        var stuck = java.util.Map.of("Alerts", java.util.List.of(software.amazon.awssdk.services.dynamodb.model.WriteRequest.builder()
                .deleteRequest(d -> d.key(row)).build()));
        doReturn(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse.builder().unprocessedItems(stuck).build())
                .when(dynamo).batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> alerts.eraseAll("a@example.com"))
                .isInstanceOf(AlertsEraseIncompleteException.class);
        org.mockito.Mockito.verify(dynamo, org.mockito.Mockito.times(3))
                .batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class));
    }

    @Test
    void retentionMustBePositiveAndReadOnlyDoublesCannotErase() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DynamoDbAlertRepository(dynamo, "Alerts", java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        AlertRepository readOnly = new AlertRepository() {
            public boolean record(Alert alert) { return false; }
            public java.util.List<Alert> find(String email) { return java.util.List.of(); }
            public java.util.List<Alert> unnotifiedBetween(java.time.Instant from, java.time.Instant to, int max) { return java.util.List.of(); }
            public boolean claimNotification(Alert alert, java.time.Instant runAt) { return false; }
            public void releaseNotification(Alert alert, java.time.Instant runAt) {}
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> readOnly.eraseAll("a@example.com"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
