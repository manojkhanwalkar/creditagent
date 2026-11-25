package com.creditagent.web.auth.otp;

import java.time.Duration;

/**
 * The notification-service did not confirm a sign-in code: the exception of {@link
 * com.creditagent.web.notify.NotificationGateway}. The message never holds the code or destination.
 *
 * <p>{@link #refused()} is true only when the message was certainly not delivered (the service answered
 * 422: the provider refused it). A timeout, a 5xx, throttling or an unreachable service may still have
 * delivered it: those sends keep counting against the limits, and their code stays valid.
 */
public class OtpDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final OtpChannel channel;
    private final boolean refused;
    private final transient Duration retryAfter;
    private final boolean lastSend;

    /** A classified failure: {@code refused} only when the message was certainly not delivered. */
    public OtpDeliveryException(OtpChannel channel, Throwable cause, boolean refused) {
        this(channel, cause, refused, null, false);
    }

    private OtpDeliveryException(OtpChannel channel, Throwable cause, boolean refused, Duration retryAfter,
            boolean lastSend) {
        super("Could not send a sign-in code by " + channel, cause);
        this.channel = channel;
        this.refused = refused;
        this.retryAfter = retryAfter;
        this.lastSend = lastSend;
    }

    /** The same failure, saying how long until another code may be asked for (the send still counts). */
    public OtpDeliveryException withRetryAfter(Duration wait) {
        return new OtpDeliveryException(channel, getCause(), refused, wait, false);
    }

    /** The same failure, for the last send this sign-in allows: no other code may be asked for. */
    public OtpDeliveryException asLastSend() {
        return new OtpDeliveryException(channel, getCause(), refused, null, true);
    }

    /** True when this was the sign-in's last allowed send, so only a new sign-in can send another. */
    public boolean lastSend() {
        return lastSend;
    }

    public OtpChannel channel() {
        return channel;
    }

    /** True when the message was certainly not delivered. */
    public boolean refused() {
        return refused;
    }

    /** Wait before another code may be asked for, when the send still counts; null when it does not. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
