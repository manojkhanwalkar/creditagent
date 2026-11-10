package com.creditagent.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.alerts.Alert;
import com.creditagent.alerts.AlertRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class AlertNotifierTest {

    static final Instant RUN = Instant.parse("2026-11-12T08:00:00Z");

    /** The Alerts table's notification semantics, in memory. */
    static class InMemoryAlerts implements AlertRepository {
        final Map<String, Alert> alerts = new ConcurrentHashMap<>();
        final Map<String, Instant> claims = new ConcurrentHashMap<>();

        static String key(Alert a) {
            return a.email() + "|" + a.location();
        }

        @Override
        public boolean record(Alert alert) {
            return alerts.putIfAbsent(key(alert), alert) == null;
        }

        @Override
        public List<Alert> find(String email) {
            return alerts.values().stream().filter(a -> a.email().equals(email)).toList();
        }

        @Override
        public List<Alert> unnotifiedBetween(Instant from, Instant to, int max) {
            return alerts.values().stream()
                    .filter(a -> !a.timestamp().isBefore(from) && a.timestamp().isBefore(to) && !claims.containsKey(key(a)))
                    .sorted(Comparator.comparing(Alert::timestamp)).limit(max).toList();
        }

        @Override
        public boolean claimNotification(Alert alert, Instant runAt) {
            return alerts.containsKey(key(alert)) && claims.putIfAbsent(key(alert), runAt) == null;
        }

        @Override
        public void releaseNotification(Alert alert, Instant runAt) {
            claims.remove(key(alert), runAt);
        }
    }

    record Sent(String to, String subject, String body) {}

    final InMemoryAlerts alerts = new InMemoryAlerts();
    final List<Sent> sent = new ArrayList<>();
    final List<Duration> pauses = new ArrayList<>();

    static NotifierProperties properties(int max) {
        return new NotifierProperties(Duration.ofDays(7), max, 4.0, null, "https://credit.example", null, null, null, null, null);
    }

    AlertNotifier notifier(EmailSender email, int max) {
        return new AlertNotifier(alerts, email, properties(max), Clock.fixed(RUN, ZoneOffset.UTC), pauses::add);
    }

    AlertNotifier notifier() {
        return notifier((to, subject, body) -> sent.add(new Sent(to, subject, body)), 100);
    }

    @Test
    void eachUserWithNewFindingsGetsOneEmailAndOnlyOnce() {
        alerts.record(new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(23))));
        alerts.record(new Alert("ada@example.com", "loc-b", RUN.minus(Duration.ofHours(2))));
        alerts.record(new Alert("bob@example.com", "loc-a", RUN.minus(Duration.ofMinutes(5))));
        alerts.record(new Alert("old@example.com", "loc-a", RUN.minus(Duration.ofDays(8)))); // older than the lookback

        assertThat(notifier().run()).isEqualTo(new AlertNotifier.Run(3, 2, 0));
        assertThat(sent).extracting(Sent::to).containsExactly("ada@example.com", "bob@example.com");
        assertThat(sent.get(0).subject()).isEqualTo(AlertEmail.SUBJECT);
        assertThat(sent.get(0).body()).contains("2 places").contains("identity protection").contains("https://credit.example");
        // One pause between the two emails, at 4 a second.
        assertThat(pauses).containsExactly(Duration.ofMillis(250));

        // The same day again (a restart, a second replica): nothing new.
        sent.clear();
        assertThat(notifier().run()).isEqualTo(new AlertNotifier.Run(0, 0, 0));
        assertThat(sent).isEmpty();
    }

    @Test
    void aFailedEmailReleasesItsAlertsForALaterRun() {
        alerts.record(new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(1))));
        alerts.record(new Alert("bob@example.com", "loc-a", RUN.minus(Duration.ofHours(1))));
        EmailSender adaFails = (to, subject, body) -> {
            if (to.startsWith("ada")) {
                throw new org.springframework.mail.MailSendException("relay down");
            }
            sent.add(new Sent(to, subject, body));
        };
        assertThat(notifier(adaFails, 100).run()).isEqualTo(new AlertNotifier.Run(2, 1, 1));
        assertThat(alerts.unnotifiedBetween(RUN.minus(Duration.ofDays(1)), RUN, 10)).extracting(Alert::email)
                .containsExactly("ada@example.com");
    }

    @Test
    void anEmailThatFailedIsSentByTheNextDaysRun() {
        alerts.record(new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(1))));
        EmailSender down = (to, subject, body) -> {
            throw new org.springframework.mail.MailSendException("relay down");
        };
        assertThat(notifier(down, 100).run()).isEqualTo(new AlertNotifier.Run(1, 0, 1));

        AlertNotifier tomorrow = new AlertNotifier(alerts, (to, s, b) -> sent.add(new Sent(to, s, b)), properties(100),
                Clock.fixed(RUN.plus(Duration.ofDays(1)), ZoneOffset.UTC), pauses::add);
        assertThat(tomorrow.run()).isEqualTo(new AlertNotifier.Run(1, 1, 0));
        assertThat(sent).extracting(Sent::to).containsExactly("ada@example.com");
    }

    @Test
    void withoutAMailRelayNothingIsReadOrClaimed() {
        alerts.record(new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(1))));
        AlertNotifier unconfigured = notifier(EmailSender.placeholder(), 100);
        assertThat(unconfigured.run()).isEqualTo(new AlertNotifier.Run(0, 0, 0, true));
        unconfigured.scheduled();
        assertThat(alerts.claims).isEmpty();
        // Once a relay is configured, the waiting alert is emailed.
        assertThat(notifier().run().emailsSent()).isEqualTo(1);
    }

    @Test
    void aDynamoDbErrorWhileClaimingReleasesThatUsersClaimsAndTheRunGoesOn() {
        Alert first = new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(3)));
        Alert second = new Alert("ada@example.com", "loc-b", RUN.minus(Duration.ofHours(2)));
        alerts.record(first);
        alerts.record(second);
        alerts.record(new Alert("bob@example.com", "loc-a", RUN.minus(Duration.ofHours(1))));
        InMemoryAlerts flaky = new InMemoryAlerts() {
            @Override
            public boolean claimNotification(Alert alert, Instant runAt) {
                if (alert.equals(second)) {
                    throw new IllegalStateException("DynamoDB timeout");
                }
                return AlertNotifierTest.this.alerts.claimNotification(alert, runAt);
            }

            @Override
            public List<Alert> unnotifiedBetween(Instant from, Instant to, int max) {
                return AlertNotifierTest.this.alerts.unnotifiedBetween(from, to, max);
            }

            @Override
            public void releaseNotification(Alert alert, Instant runAt) {
                AlertNotifierTest.this.alerts.releaseNotification(alert, runAt);
                throw new IllegalStateException("and the release times out too"); // logged, not thrown
            }
        };
        AlertNotifier notifier = new AlertNotifier(flaky, (to, s, b) -> sent.add(new Sent(to, s, b)), properties(100),
                Clock.fixed(RUN, ZoneOffset.UTC), pauses::add);
        assertThat(notifier.run()).isEqualTo(new AlertNotifier.Run(3, 1, 1));
        assertThat(sent).extracting(Sent::to).containsExactly("bob@example.com");
        assertThat(alerts.claims).doesNotContainKey(InMemoryAlerts.key(first)); // released for the next run
    }

    @Test
    void alertsAnotherReplicaClaimedAreSkipped() {
        Alert taken = new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(1)));
        alerts.record(taken);
        AlertRepository racing = new InMemoryAlerts() {
            @Override
            public List<Alert> unnotifiedBetween(Instant from, Instant to, int max) {
                return List.of(taken); // read before the other replica claimed it
            }

            @Override
            public boolean claimNotification(Alert alert, Instant runAt) {
                return false;
            }
        };
        AlertNotifier notifier = new AlertNotifier(racing, (to, s, b) -> sent.add(new Sent(to, s, b)), properties(100),
                Clock.fixed(RUN, ZoneOffset.UTC), pauses::add);
        assertThat(notifier.run()).isEqualTo(new AlertNotifier.Run(1, 0, 0));
        assertThat(sent).isEmpty();
    }

    @Test
    void anInterruptedRunStopsAndGivesBackWhatItClaimed() {
        alerts.record(new Alert("ada@example.com", "loc-a", RUN.minus(Duration.ofHours(2))));
        alerts.record(new Alert("bob@example.com", "loc-a", RUN.minus(Duration.ofHours(1))));
        AlertNotifier notifier = new AlertNotifier(alerts, (to, s, b) -> sent.add(new Sent(to, s, b)), properties(100),
                Clock.fixed(RUN, ZoneOffset.UTC), duration -> {
                    throw new InterruptedException();
                });
        try {
            assertThat(notifier.run()).isEqualTo(new AlertNotifier.Run(2, 1, 0));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(alerts.unnotifiedBetween(RUN.minus(Duration.ofDays(1)), RUN, 10)).extracting(Alert::email)
                .containsExactly("bob@example.com");
    }

    @Test
    void aRunReadsAtMostItsCapAndAFailedRunIsLoggedNotThrown() {
        for (int i = 0; i < 5; i++) {
            alerts.record(new Alert("u" + i + "@example.com", "loc-a", RUN.minus(Duration.ofMinutes(i + 1))));
        }
        assertThat(notifier((to, s, b) -> sent.add(new Sent(to, s, b)), 3).run().alerts()).isEqualTo(3);

        AlertRepository broken = new InMemoryAlerts() {
            @Override
            public List<Alert> unnotifiedBetween(Instant from, Instant to, int max) {
                throw new IllegalStateException("DynamoDB down");
            }
        };
        new AlertNotifier(broken, (to, s, b) -> { }, properties(10), Clock.fixed(RUN, ZoneOffset.UTC), d -> { }).scheduled();
        notifier().scheduled();
    }

    @Test
    void propertiesHaveSafeDefaultsAndRefuseNonsense() {
        NotifierProperties defaults = new NotifierProperties(null, null, null, " ", null, null, null, null, " ", null);
        assertThat(defaults.lookback()).isEqualTo(Duration.ofDays(7));
        assertThat(defaults.from()).isEqualTo("alerts@creditagent.local");
        assertThat(defaults.appUrl()).isEmpty();
        assertThat(defaults.alertsTable()).isEqualTo("Alerts");
        assertThat(defaults.createTables()).isFalse();
        assertThatThrownBy(() -> new NotifierProperties(Duration.ZERO, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifierProperties(null, null, 0.0, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
