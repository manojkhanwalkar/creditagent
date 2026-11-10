package com.creditagent.web.alerts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dark-web alerts ({@code credit.alerts.*}).
 *
 * @param url the Alerts service, e.g. {@code http://localhost:8092}
 * @param timeout per try for {@code GET /api/v1/alerts}, which retries transient failures
 * @param loginTimeout the one try a login waits for alerts (no retry)
 */
@ConfigurationProperties("credit.alerts")
public record AlertsProperties(String url, Duration timeout, Duration loginTimeout) {

    public AlertsProperties {
        url = url == null || url.isBlank() ? "http://localhost:8092" : url;
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        loginTimeout = loginTimeout == null ? Duration.ofMillis(1500) : loginTimeout;
    }
}
