package com.creditagent.notifications;

import com.creditagent.notifications.NotificationStore.Notification;
import com.creditagent.notifications.NotificationStore.Status;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Validates, renders and sends one notification, at most once per idempotency key while the key is
 * remembered:
 *
 * <ol>
 *   <li>Everything is checked and the text rendered before anything is stored.
 *   <li>A conditional put reserves the key (PENDING, with a lease). If the key is held: the same
 *       request already SENT answers its original id; a different request is a conflict; a PENDING
 *       one still inside its lease is in progress.
 *   <li>The provider is called; on success the record becomes SENT. On any failure the reservation is
 *       deleted, so the caller can retry with the same key.
 * </ol>
 *
 * <p>A request that stops between the provider call and the SENT update leaves a PENDING record. Once
 * its lease runs out the same request may take it over and send again: at-least-once in that window,
 * at-most-once otherwise.
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    static final int MAX_KEY_LENGTH = 128;
    private static final Pattern KEY_CHARS = Pattern.compile("^[\\x21-\\x7e]{1," + MAX_KEY_LENGTH + "}$");

    /** The answer to an accepted send, first time or repeat. */
    public record Accepted(String id) {}

    /** The key was used for a different request. */
    public static class KeyReusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        KeyReusedException() {
            super("The Idempotency-Key was used for a different request");
        }
    }

    /** Another request with this key is sending now; its outcome is not known yet. */
    public static class InProgressException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        InProgressException(long retryAfterSeconds) {
            super("A send with this Idempotency-Key is in progress");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final NotificationStore store;
    private final Map<Channel, Sender> senders;
    private final byte[] hashKey;
    private final Clock clock;
    private final Duration lease;
    private final Duration retention;

    /**
     * @param senders one per channel
     * @param secret keys the stored hashes (the service token), so neither a key nor a request can be
     *     recovered from the table by guessing
     * @param lease how long a PENDING record belongs to its request; longer than a send can take
     * @param retention how long an accepted key is remembered (the table's TTL)
     */
    public NotificationService(NotificationStore store, Map<Channel, Sender> senders, String secret, Clock clock,
            Duration lease, Duration retention) {
        this.store = Objects.requireNonNull(store);
        this.senders = new EnumMap<>(senders);
        for (Channel channel : Channel.values()) {
            Objects.requireNonNull(this.senders.get(channel), "no sender for " + channel);
        }
        this.hashKey = sha256("notification-service/hash-key/v1|" + Objects.requireNonNull(secret));
        this.clock = Objects.requireNonNull(clock);
        this.lease = Objects.requireNonNull(lease);
        this.retention = Objects.requireNonNull(retention);
    }

    /**
     * @throws InvalidNotificationException for a request that can never be sent (400)
     * @throws KeyReusedException (409), {@link InProgressException} (503), {@link DeliveryException}
     *     (422 when refused, else 503), or {@link SdkException} when the table is unavailable (503)
     */
    public Accepted send(String idempotencyKey, Channel channel, String to, Template template, Map<String, String> params) {
        if (idempotencyKey == null || !KEY_CHARS.matcher(idempotencyKey).matches()) {
            throw new InvalidNotificationException("Idempotency-Key must be 1 to " + MAX_KEY_LENGTH
                    + " printable ASCII characters without spaces");
        }
        if (channel == null || template == null) {
            throw new InvalidNotificationException("channel and template are required");
        }
        if (to == null || to.length() > Channel.MAX_TO_LENGTH || !channel.accepts(to)) {
            throw new InvalidNotificationException(channel == Channel.EMAIL
                    ? "to must be an email address" : "to must be an E.164 number (+ and 7 to 15 digits)");
        }
        Message message = template.validateAndRender(params, channel);

        Instant now = clock.instant();
        String key = hmac("key|" + idempotencyKey);
        String requestHash = hmac(canonical(channel, to, template, params));
        Notification record = new Notification(key, requestHash, UUID.randomUUID().toString(), channel, template,
                channel.mask(to), Status.PENDING, null, now, now.plus(lease), now.plus(retention));

        Optional<Notification> held = store.reserve(record, now);
        if (held.isPresent()) {
            return repeat(held.get(), requestHash, now);
        }
        String providerId;
        try {
            providerId = senders.get(channel).send(to, message);
        } catch (DeliveryException e) {
            releaseQuietly(record);
            log.warn("{} {} to {} {} (id {})", template, channel, record.destination(),
                    e.refused() ? "refused by the provider" : "not confirmed by the provider", record.id());
            throw e;
        } catch (RuntimeException e) {
            releaseQuietly(record);
            throw new DeliveryException(channel, e, false);
        }
        try {
            store.markSent(key, record.id(), providerId);
        } catch (SdkException e) {
            // Sent: answer so. The record stays PENDING; a repeat after the lease would send again.
            log.warn("Sent {} (id {}) but could not record it: {}", template, record.id(), e.getClass().getSimpleName());
        }
        log.info("Sent {} {} to {} (id {})", template, channel, record.destination(), record.id());
        return new Accepted(record.id());
    }

    private Accepted repeat(Notification held, String requestHash, Instant now) {
        if (!MessageDigest.isEqual(held.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new KeyReusedException();
        }
        if (held.status() == Status.SENT) {
            return new Accepted(held.id());
        }
        long wait = Math.max(1, Duration.between(now, held.leaseUntil()).toSeconds());
        throw new InProgressException(wait);
    }

    private void releaseQuietly(Notification record) {
        try {
            store.release(record.key(), record.id());
        } catch (SdkException e) {
            // The PENDING record lapses with its lease; then the same request may send again.
            log.warn("Could not release id {}: {}", record.id(), e.getClass().getSimpleName());
        }
    }

    /** Length-prefixed fields in a fixed order, params sorted by name: one request, one string. */
    static String canonical(Channel channel, String to, Template template, Map<String, String> params) {
        StringBuilder out = new StringBuilder("v1");
        field(out, channel.name());
        field(out, to);
        field(out, template.name());
        for (Map.Entry<String, String> param : new TreeMap<>(params == null ? Map.<String, String>of() : params).entrySet()) {
            field(out, param.getKey());
            field(out, param.getValue());
        }
        return out.toString();
    }

    private static void field(StringBuilder out, String value) {
        out.append('|').append(value.length()).append(':').append(value);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
