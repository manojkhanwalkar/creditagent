package com.creditagent.notifier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How alert emails are sent ({@code credit.notifications}).
 *
 * @param mode {@code local} (the SMTP relay, the default) or {@code remote} (the notification-service)
 * @param url the notification-service base URL
 * @param timeout longest one call to the service may take; above the service's own worst case (about 8 s)
 */
@ConfigurationProperties("credit.notifications")
public record NotificationsProperties(Mode mode, String url, Duration timeout) {

    public enum Mode { LOCAL, REMOTE }

    public NotificationsProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        url = url == null || url.isBlank() ? "http://localhost:8094" : url.strip();
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("credit.notifications.timeout must be positive");
        }
    }
}
