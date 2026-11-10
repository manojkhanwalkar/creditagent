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
 */
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    /** What one run did. {@code skipped}: no mail relay is configured, so nothing was read or claimed. */
    public record Run(int alerts, int emailsSent, int emailsFailed, boolean skipped) {

        public Run(int alerts, int emailsSent, int emailsFailed) {
            this(alerts, emailsSent, emailsFailed, false);
        }
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

    public AlertNotifier(AlertRepository alerts, EmailSender email, NotifierProperties properties, Clock clock, Pause pause) {
        this.alerts = alerts;
        this.email = email;
        this.properties = properties;
        this.clock = clock;
        this.pause = pause;
    }

    @Scheduled(cron = "${credit.notifier.cron:0 0 8 * * *}", zone = "UTC")
    void scheduled() {
        try {
            Run run = run();
            if (run.skipped()) {
                log.warn("Alert emails: no mail relay configured (MAIL_HOST); alerts stay unclaimed until one is");
                return;
            }
            // Counts only: no addresses in logs.
            log.info("Alert emails: {} new alerts, {} emails sent, {} failed", run.alerts(), run.emailsSent(), run.emailsFailed());
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
        for (Map.Entry<String, List<Alert>> user : byEmail.entrySet()) {
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
                if (sent + failed > 0 && !pauseFor(gap)) {
                    release(claimed, now);
                    break;
                }
                email.send(user.getKey(), AlertEmail.SUBJECT, AlertEmail.body(claimed, properties.appUrl()));
                sent++;
            } catch (RuntimeException e) {
                // A failed claim (DynamoDB) or send: give this user's claims back and go on with the next.
                failed++;
                release(claimed, now);
                log.warn("An alert email could not be sent: {}", e.getClass().getSimpleName());
            }
        }
        return new Run(found.size(), sent, failed);
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
