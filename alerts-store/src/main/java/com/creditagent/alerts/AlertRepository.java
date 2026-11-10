package com.creditagent.alerts;

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
}
