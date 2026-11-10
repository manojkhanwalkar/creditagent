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
}
