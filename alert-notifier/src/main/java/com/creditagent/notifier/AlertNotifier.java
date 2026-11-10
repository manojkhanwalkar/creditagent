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
 * Once a day: every alert first found in the {@code window} (24 hours) before the run and not yet
 * notified is claimed, and each user gets one email about all of theirs.
 *
 * <p>Claim, then send: a conditional claim per alert means no alert is emailed twice, even if two
 * replicas or two runs overlap. If the email cannot be sent, the claims are released so a later run
 * within the window can retry. If the process dies between sending and the end of the run, the claim
 * stays and the user is not emailed again: at most once.
 */
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    /** What one run did. */
    public record Run(int alerts, int emailsSent, int emailsFailed) {}

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
            // Counts only: no addresses in logs.
            log.info("Alert emails: {} new alerts, {} emails sent, {} failed", run.alerts(), run.emailsSent(), run.emailsFailed());
        } catch (RuntimeException e) {
            log.warn("Alert email run failed; the next one runs on schedule: {}", e.getClass().getSimpleName());
        }
    }

    public Run run() {
        Instant now = clock.instant();
        List<Alert> found = alerts.unnotifiedBetween(now.minus(properties.window()), now, properties.maxAlertsPerRun());
        Map<String, List<Alert>> byEmail = new LinkedHashMap<>();
        for (Alert alert : found) {
            byEmail.computeIfAbsent(alert.email(), e -> new ArrayList<>()).add(alert);
        }
        Duration gap = Duration.ofNanos((long) (1_000_000_000L / properties.sendsPerSecond()));
        int sent = 0;
        int failed = 0;
        for (Map.Entry<String, List<Alert>> user : byEmail.entrySet()) {
            List<Alert> claimed = user.getValue().stream().filter(alert -> alerts.claimNotification(alert, now)).toList();
            if (claimed.isEmpty()) {
                continue; // another run or replica has them
            }
            if (sent + failed > 0 && !pauseFor(gap)) {
                claimed.forEach(alert -> alerts.releaseNotification(alert, now));
                break;
            }
            try {
                email.send(user.getKey(), AlertEmail.SUBJECT, AlertEmail.body(claimed, properties.appUrl()));
                sent++;
            } catch (RuntimeException e) {
                failed++;
                claimed.forEach(alert -> alerts.releaseNotification(alert, now));
                log.warn("An alert email could not be sent: {}", e.getClass().getSimpleName());
            }
        }
        return new Run(found.size(), sent, failed);
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
