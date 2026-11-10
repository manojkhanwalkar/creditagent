package com.creditagent.alerts;

import java.time.Instant;
import java.util.List;

/** The Alerts table. Production implementation: {@link DynamoDbAlertRepository}. */
public interface AlertRepository {

    /**
     * Stores the alert unless one for the same email and location exists (atomically), so a monitor
     * that finds the same exposure every cycle writes it once.
     *
     * @return true if this call stored it
     */
    boolean record(Alert alert);

    /** Every alert for this email, oldest first. */
    List<Alert> find(String email);

    /**
     * Alerts first found in {@code [from, to)} that no notification has claimed yet, oldest first.
     *
     * @param max the most to return; the rest wait for the next run
     */
    List<Alert> unnotifiedBetween(Instant from, Instant to, int max);

    /**
     * Claims the alert for the notification run {@code runAt}, atomically: only one run (or replica)
     * can claim an alert.
     *
     * @return true if this call claimed it
     */
    boolean claimNotification(Alert alert, Instant runAt);

    /** Gives back a claim made by run {@code runAt} (the email could not be sent), so a later run may retry. */
    void releaseNotification(Alert alert, Instant runAt);
}
