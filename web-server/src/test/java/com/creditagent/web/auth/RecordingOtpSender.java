package com.creditagent.web.auth;

import com.creditagent.web.auth.otp.OtpSender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A mock delivery channel: keeps every code "sent" so a test can type it in, as the user would. */
public final class RecordingOtpSender implements OtpSender {

    /** One delivered message. */
    public record Sent(String destination, String code, Duration validFor) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** When set, every send throws it (a provider outage). */
    public volatile RuntimeException failure;

    @Override
    public void send(String destination, String code, Duration validFor) {
        if (failure != null) {
            throw failure;
        }
        sent.add(new Sent(destination, code, validFor));
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
