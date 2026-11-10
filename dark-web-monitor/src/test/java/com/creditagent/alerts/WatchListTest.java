package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

class WatchListTest {

    static ScanResponse page(String email, String lastKey) {
        ScanResponse.Builder response = ScanResponse.builder()
                .items(List.of(Map.of("email", AttributeValue.fromS(email)))).scannedCount(1);
        if (lastKey != null) {
            response.lastEvaluatedKey(Map.of("userId", AttributeValue.fromS(lastKey)));
        }
        return response.build();
    }

    @Test
    void aFailedPageIsReadAgainNextCycleNotSkippedOrRestartedFromTheTop() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        when(dynamo.scan(any(ScanRequest.class)))
                .thenReturn(page("a@x.io", "a"), page("b@x.io", "b"), page("b@x.io", "b"), page("c@x.io", null));
        WatchList.Source source = WatchList.users(dynamo, "users");
        List<String> checked = new ArrayList<>();
        boolean[] failOnce = {true};

        assertThatThrownBy(() -> source.forEachPage(1, 100, emails -> {
            if (emails.contains("b@x.io") && failOnce[0]) {
                failOnce[0] = false;
                throw new IllegalStateException("Alerts table down");
            }
            checked.addAll(emails);
        })).isInstanceOf(IllegalStateException.class);
        source.forEachPage(1, 100, checked::addAll);

        assertThat(checked).containsExactly("a@x.io", "b@x.io", "c@x.io");
        ArgumentCaptor<ScanRequest> scans = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo, org.mockito.Mockito.times(4)).scan(scans.capture());
        // The retry starts after "a", the last page that succeeded.
        assertThat(scans.getAllValues().get(2).exclusiveStartKey()).containsEntry("userId", AttributeValue.fromS("a"));
        assertThat(scans.getAllValues().get(0).filterExpression()).contains("attribute_exists(#pw)");
    }

    @Test
    void theDefaultPlaceholderInventsNothing() {
        MonitorProperties defaults = new MonitorProperties(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(defaults.placeholderRate()).isZero();
    }
}
