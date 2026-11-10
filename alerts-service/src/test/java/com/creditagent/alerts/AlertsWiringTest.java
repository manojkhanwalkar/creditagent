package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class AlertsWiringTest {

    @Test
    void theRepositoryReadsTheConfiguredTable() {
        AlertsConfiguration configuration = new AlertsConfiguration();
        try (DynamoDbClient client = configuration.dynamoDbClient("http://localhost:1", "us-east-1", Duration.ofSeconds(1),
                "Alerts", false)) {
            assertThat(configuration.alertRepository(client, "Alerts")).isInstanceOf(DynamoDbAlertRepository.class);
        }
    }

    @Test
    void theLookupNeverPrintsTheEmail() {
        assertThat(new AlertsController.LookupRequest("ada@example.com").toString()).doesNotContain("ada");
    }
}
