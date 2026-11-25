package com.creditagent.notifier;

import com.creditagent.alerts.Alert;
import com.creditagent.alerts.AlertRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Once a day: every alert no email has covered yet, first found within the {@code lookback} (7 days)
 * before the run, is claimed, and each user gets one email about all of theirs. Normally that is the
 * last 24 hours' alerts: the previous run claimed everything older. The longer lookback is what lets a
 * failed send, a capped or interrupted run, or a day the service was down catch up the next day.
 *
 * <p>Claim, then send: a conditional claim per alert means no alert is emailed twice, even if two
 * replicas or two runs overlap. If the email cannot be sent, or the claims cannot all be made, the
 * claims are released so the next run retries. If the process dies between sending and the end of the
 * run, the claim stays and the user is not emailed again: at most once.
 *
 * <p>Emails are for ID Protection subscribers only. A free user's alerts are claimed without an email
 * ({@code notSubscribed}), so they do not fill every later run's cap; the user still sees that something
 * was found when they sign in, and the details once they subscribe. If whether an address subscribes
 * cannot be told, its alerts are left unclaimed for the next run.
 */
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    /**
     * What one run did. {@code skipped}: the notification-service is not configured, so nothing was read or claimed.
     * {@code notSubscribed}: users not emailed because they have no ID Protection. {@code unknown}: users
     * left for the next run because whether they subscribe could not be read.
     */
    public record Run(int alerts, int emailsSent, int emailsFailed, boolean skipped, int notSubscribed, int unknown) {

        public Run(int alerts, int emailsSent, int emailsFailed) {
            this(alerts, emailsSent, emailsFailed, false, 0, 0);
        }

        public Run(int alerts, int emailsSent, int emailsFailed, boolean skipped) {
            this(alerts, emailsSent, emailsFailed, skipped, 0, 0);
        }
    }

    /** Whether an address belongs to a user with active ID Protection; may throw if it cannot be told. */
    @FunctionalInterface
    public interface Subscribers {
        boolean idProtection(String email, Instant now);
    }

    /** Pauses between emails; replaced in tests. */
    @FunctionalInterface
    public interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final AlertRepository alerts;
    private final EmailSender email;
    private final NotifierProperties properties;
    private final Clock clock;
    private final Pause pause;
    private final Subscribers subscribers;

    public AlertNotifier(AlertRepository alerts, EmailSender email, NotifierProperties properties, Clock clock, Pause pause,
            Subscribers subscribers) {
        this.alerts = alerts;
        this.email = email;
        this.properties = properties;
        this.clock = clock;
        this.pause = pause;
        this.subscribers = java.util.Objects.requireNonNull(subscribers);
    }

    @Scheduled(cron = "${credit.notifier.cron:0 0 8 * * *}", zone = "UTC")
    void scheduled() {
        try {
            Run run = run();
            if (run.skipped()) {
                log.warn("Alert emails: no notification-service configured (INTERNAL_API_TOKEN); alerts stay unclaimed until it is");
                return;
            }
            // Counts only: no addresses in logs.
            log.info("Alert emails: {} new alerts, {} emails sent, {} failed, {} users without ID Protection, {} left for"
                    + " the next run (subscription unknown)", run.alerts(), run.emailsSent(), run.emailsFailed(),
                    run.notSubscribed(), run.unknown());
        } catch (RuntimeException e) {
            log.warn("Alert email run failed; the next one runs on schedule: {}", e.getClass().getSimpleName());
        }
    }

    public Run run() {
        if (!email.configured()) {
            return new Run(0, 0, 0, true);
        }
        Instant now = clock.instant();
        List<Alert> found = alerts.unnotifiedBetween(now.minus(properties.lookback()), now, properties.maxAlertsPerRun());
        Map<String, List<Alert>> byEmail = new LinkedHashMap<>();
        for (Alert alert : found) {
            byEmail.computeIfAbsent(alert.email(), e -> new ArrayList<>()).add(alert);
        }
        Duration gap = Duration.ofNanos((long) (1_000_000_000L / properties.sendsPerSecond()));
        int sent = 0;
        int failed = 0;
        int notSubscribed = 0;
        int unknown = 0;
        for (Map.Entry<String, List<Alert>> user : byEmail.entrySet()) {
            boolean subscribed;
            try {
                subscribed = subscribers.idProtection(user.getKey(), now);
            } catch (RuntimeException e) {
                // Nothing claimed: the next run asks again.
                unknown++;
                log.warn("Whether a user has ID Protection could not be read: {}", e.getClass().getSimpleName());
                continue;
            }
            List<Alert> claimed = new ArrayList<>();
            try {
                for (Alert alert : user.getValue()) {
                    if (alerts.claimNotification(alert, now)) {
                        claimed.add(alert);
                    }
                }
                if (claimed.isEmpty()) {
                    continue; // another run or replica has them
                }
                if (!subscribed) {
                    notSubscribed++;
                    continue; // claimed, not emailed: the in-app alert covers a free user
                }
                if (sent + failed > 0 && !pauseFor(gap)) {
                    release(claimed, now);
                    break;
                }
                email.sendAlert(user.getKey(), claimed, now, properties.appUrl());
                sent++;
            } catch (RuntimeException e) {
                // A failed claim (DynamoDB) or send: give this user's claims back and go on with the next.
                failed++;
                release(claimed, now);
                log.warn("An alert email could not be sent: {}", e.getClass().getSimpleName());
            }
        }
        return new Run(found.size(), sent, failed, false, notSubscribed, unknown);
    }

    /** Best effort: a claim that cannot be released stays, and that alert is not emailed (at most once). */
    private void release(List<Alert> claimed, Instant runAt) {
        for (Alert alert : claimed) {
            try {
                alerts.releaseNotification(alert, runAt);
            } catch (RuntimeException e) {
                log.warn("An alert claim could not be released: {}", e.getClass().getSimpleName());
            }
        }
    }

    private boolean pauseFor(Duration gap) {
        try {
            pause.sleep(gap);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
