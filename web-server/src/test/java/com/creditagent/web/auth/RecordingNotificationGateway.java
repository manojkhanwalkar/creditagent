package com.creditagent.web.auth;

import com.creditagent.web.notify.NotificationGateway;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mock notification-service: keeps every sign-in code "sent" so a test can type it in, as the user
 * would.
 */
public final class RecordingNotificationGateway implements NotificationGateway {

    /** One delivered message. */
    public record Sent(String destination, String code, Duration validFor) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** When set, every send throws it (a service or provider outage). */
    public volatile RuntimeException failure;

    @Override
    public void send(Notification n) {
        if (failure != null) {
            throw failure;
        }
        sent.add(new Sent(n.to(), n.params().get("code"),
                Duration.ofSeconds(Long.parseLong(n.params().get("validSeconds")))));
    }

    /** The code most recently sent, to anyone. */
    public String lastCode() {
        if (sent.isEmpty()) {
            throw new IllegalStateException("No code was sent");
        }
        return sent.get(sent.size() - 1).code();
    }

    public Sent last() {
        return sent.get(sent.size() - 1);
    }
}
