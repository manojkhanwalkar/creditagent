package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.notifications.NotificationStore.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

/** Idempotency: a key sends at most once while remembered, and only accepted sends are remembered. */
class NotificationServiceTest {

    static final String SECRET = "service-token-service-token-service-token";
    static final Map<String, String> OTP = Map.of("code", "042917", "validSeconds", "300");

    final InMemoryNotificationStore store = new InMemoryNotificationStore();
    final List<String> sent = new ArrayList<>();
    final AtomicReference<RuntimeException> failNext = new AtomicReference<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T10:00:00Z"));

    final Sender sender = (to, message) -> {
        RuntimeException failure = failNext.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        sent.add(to + "|" + message.text());
        return "provider-" + sent.size();
    };

    NotificationService service(NotificationStore store) {
        return new NotificationService(store, Map.of(Channel.EMAIL, sender, Channel.SMS, sender), SECRET, clock,
                Duration.ofSeconds(60), Duration.ofDays(30));
    }

    @Test
    void aRepeatOfAnAcceptedKeyAnswersTheOriginalIdWithoutSendingAgain() {
        NotificationService service = service(store);
        var first = service.send("otp:c1:1", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP);
        var again = service.send("otp:c1:1", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP);
        assertThat(again).isEqualTo(first);
        assertThat(sent).hasSize(1);

        var record = store.records.values().iterator().next();
        assertThat(record.status()).isEqualTo(Status.SENT);
        assertThat(record.providerMessageId()).isEqualTo("provider-1");
        assertThat(record.destination()).isEqualTo("+1******0100");
        assertThat(record.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        // Neither the raw key (a challenge id) nor the code nor the number is stored.
        assertThat(record.toString()).doesNotContain("c1").doesNotContain("042917").doesNotContain("5555550100");
        assertThat(record.key()).hasSize(64);
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        NotificationService service = service(store);
        service.send("otp:c1:1", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP);
        assertThatThrownBy(() -> service.send("otp:c1:1", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN,
                Map.of("code", "999999", "validSeconds", "300")))
                .isInstanceOf(NotificationService.KeyReusedException.class);
        assertThatThrownBy(() -> service.send("otp:c1:1", Channel.EMAIL, "ada@example.com", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(NotificationService.KeyReusedException.class);
        assertThat(sent).hasSize(1);
    }

    @Test
    void aRefusedSendFreesTheKeySoTheSameKeyMaySendAgain() {
        NotificationService service = service(store);
        failNext.set(new DeliveryException(Channel.SMS, new RuntimeException("400"), true));
        assertThatThrownBy(() -> service.send("otp:c1:1", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(DeliveryException.class).satisfies(e -> assertThat(((DeliveryException) e).refused()).isTrue());
        assertThat(store.records).isEmpty();

        // N1 gives the send number back after a refusal: the same key, now by email, must go.
        service.send("otp:c1:1", Channel.EMAIL, "ada@example.com", Template.OTP_SIGN_IN, OTP);
        assertThat(sent).hasSize(1);
    }

    @Test
    void anUnknownOutcomeAlsoFreesTheKeyAndAnyOtherFailureIsUnknown() {
        NotificationService service = service(store);
        failNext.set(new DeliveryException(Channel.SMS, SdkClientException.create("timeout"), false));
        assertThatThrownBy(() -> service.send("k", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isFalse());
        assertThat(store.records).isEmpty();

        failNext.set(new IllegalStateException("bug in a sender"));
        assertThatThrownBy(() -> service.send("k", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(DeliveryException.class)
                .satisfies(e -> assertThat(((DeliveryException) e).refused()).isFalse());
        assertThat(store.records).isEmpty();
    }

    @Test
    void aSendInProgressIs503UntilItsLeaseRunsOutThenTheSameRequestTakesOver() {
        NotificationService service = service(store);
        // A replica reserved the key and stopped before it could record the outcome.
        NotificationStore stuck = new InMemoryNotificationStore() {
            @Override
            public void markSent(String key, String id, String providerMessageId) {
                throw SdkClientException.create("dynamodb down");
            }
        };
        NotificationService crashed = service(stuck);
        crashed.send("k", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP); // sent; record stays PENDING
        NotificationService again = service(stuck);
        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> again.send("k", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(NotificationService.InProgressException.class)
                .satisfies(e -> assertThat(((NotificationService.InProgressException) e).retryAfterSeconds()).isEqualTo(50));
        // A different body is still a conflict, lease or not.
        assertThatThrownBy(() -> again.send("k", Channel.SMS, "+15555550199", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(NotificationService.KeyReusedException.class);

        clock.advance(Duration.ofSeconds(51));
        again.send("k", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP); // at-least-once: sent again
        assertThat(sent).hasSize(2);
        assertThat(service).isNotNull();
    }

    @Test
    void aReleaseThatFailsStillReportsTheDeliveryFailure() {
        NotificationStore failingRelease = new InMemoryNotificationStore() {
            @Override
            public void release(String key, String id) {
                throw SdkClientException.create("dynamodb down");
            }
        };
        failNext.set(new DeliveryException(Channel.EMAIL, new RuntimeException(), true));
        assertThatThrownBy(() -> service(failingRelease).send("k", Channel.EMAIL, "ada@example.com", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(DeliveryException.class);
    }

    @Test
    void aStoreFailureBeforeSendingSendsNothing() {
        NotificationStore down = new InMemoryNotificationStore() {
            @Override
            public java.util.Optional<Notification> reserve(Notification record, Instant now) {
                throw SdkClientException.create("dynamodb down");
            }
        };
        assertThatThrownBy(() -> service(down).send("k", Channel.EMAIL, "ada@example.com", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(SdkClientException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void invalidRequestsAreRefusedBeforeAnythingIsStoredOrSent() {
        NotificationService service = service(store);
        assertThatThrownBy(() -> service.send(null, Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .isInstanceOf(InvalidNotificationException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.send("k".repeat(129), Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("Idempotency-Key");
        assertThat(service.send("k".repeat(128), Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP)).isNotNull();
        assertThatThrownBy(() -> service.send("a key", Channel.SMS, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.send("k2", null, "+15555550100", Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("channel and template");
        assertThatThrownBy(() -> service.send("k2", Channel.SMS, "+15555550100", null, OTP))
                .hasMessageContaining("channel and template");
        assertThatThrownBy(() -> service.send("k2", Channel.SMS, "5555550100", Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("E.164").hasMessageNotContaining("5555550100");
        assertThatThrownBy(() -> service.send("k2", Channel.EMAIL, "not-an-email", Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("email address");
        assertThatThrownBy(() -> service.send("k2", Channel.EMAIL, null, Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("email address");
        assertThatThrownBy(() -> service.send("k2", Channel.EMAIL, "a".repeat(250) + "@example.com", Template.OTP_SIGN_IN, OTP))
                .hasMessageContaining("email address");
        assertThat(sent).hasSize(1);
        assertThat(store.records).hasSize(1);
    }

    @Test
    void theCanonicalFormIsUnambiguousAndIndependentOfParamOrder() {
        String a = NotificationService.canonical(Channel.EMAIL, "a@b.co", Template.OTP_SIGN_IN,
                new java.util.LinkedHashMap<>(Map.of("code", "1", "validSeconds", "2")));
        java.util.LinkedHashMap<String, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put("validSeconds", "2");
        reversed.put("code", "1");
        assertThat(NotificationService.canonical(Channel.EMAIL, "a@b.co", Template.OTP_SIGN_IN, reversed)).isEqualTo(a);
        assertThat(NotificationService.canonical(Channel.EMAIL, "a@b.co", Template.OTP_SIGN_IN, Map.of("code", "1|2")))
                .isNotEqualTo(NotificationService.canonical(Channel.EMAIL, "a@b.co", Template.OTP_SIGN_IN, Map.of("code", "1", "|2", "")));
        assertThat(NotificationService.canonical(Channel.EMAIL, "a@b.co", Template.OTP_SIGN_IN, null)).isEqualTo("v1|5:EMAIL|6:a@b.co|11:OTP_SIGN_IN");
    }

    @Test
    void everyChannelNeedsASender() {
        assertThatThrownBy(() -> new NotificationService(store, Map.of(Channel.EMAIL, sender), SECRET, clock,
                Duration.ofSeconds(60), Duration.ofDays(30))).isInstanceOf(NullPointerException.class).hasMessageContaining("SMS");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
