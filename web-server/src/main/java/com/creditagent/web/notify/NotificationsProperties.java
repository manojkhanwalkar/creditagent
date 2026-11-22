package com.creditagent.web.notify;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where sign-in codes are sent from ({@code credit.notifications}).
 *
 * @param mode {@code local} (SES and SNS from this process, the default) or {@code remote} (through the
 *     notification-service; SES and SNS are then not built)
 * @param url the notification-service base URL
 * @param timeout longest one call to the service may take; above the service's own worst case (about 8 s)
 */
@ConfigurationProperties("credit.notifications")
public record NotificationsProperties(Mode mode, String url, Duration timeout) {

    public static final String MODE = "credit.notifications.mode";

    public enum Mode { LOCAL, REMOTE }

    public NotificationsProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        url = url == null || url.isBlank() ? "http://localhost:8094" : url.strip();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("credit.notifications.timeout must be positive");
        }
    }
}
