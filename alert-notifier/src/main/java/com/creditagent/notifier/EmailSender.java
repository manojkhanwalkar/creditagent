package com.creditagent.notifier;

import com.creditagent.alerts.Alert;
import java.time.Instant;
import java.util.List;

/**
 * Sends the dark-web alert email: through the notification-service ({@link RemoteEmailSender}), or the
 * {@link #placeholder()} when the service token is not set. Kept as an interface so tests can record sends.
 */
@FunctionalInterface
public interface EmailSender {

    /**
     * Sends the dark-web alert for {@code alerts} (claimed in the run at {@code runAt}).
     *
     * @throws RuntimeException if the alert was not accepted for delivery
     */
    void sendAlert(String to, List<Alert> alerts, Instant runAt, String appUrl);

    /** False for the placeholder: nothing is sent, so nothing may be marked as notified. */
    default boolean configured() {
        return true;
    }

    /**
     * The placeholder when the notification-service is not configured (no {@code INTERNAL_API_TOKEN}):
     * refuses to send, and says it is not configured, so the notifier leaves every alert unclaimed until
     * it is.
     */
    static EmailSender placeholder() {
        return new EmailSender() {
            @Override
            public void sendAlert(String to, List<Alert> alerts, Instant runAt, String appUrl) {
                throw new IllegalStateException("No notification-service configured (INTERNAL_API_TOKEN)");
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }
}
