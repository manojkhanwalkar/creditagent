package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;

class AuthConfigurationTest {

    @Test
    void dynamoDbCallsAreBoundedAndRetriedWithBackoff() {
        AuthProperties.DynamoDb config = new AuthProperties.DynamoDb(null, "us-east-1", "u", "s", false, null);
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(5));

        ClientOverrideConfiguration overrides = AuthConfiguration.overrides(config);

        assertThat(overrides.apiCallTimeout()).contains(Duration.ofSeconds(5));
        assertThat(overrides.apiCallAttemptTimeout()).contains(Duration.ofMillis(2500));
        // STANDARD: 3 attempts, exponential backoff with jitter.
        assertThat(overrides.retryMode()).contains(RetryMode.STANDARD);
    }
}
