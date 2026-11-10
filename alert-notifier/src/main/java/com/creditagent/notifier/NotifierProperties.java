package com.creditagent.notifier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The alert notifier ({@code credit.notifier.*}).
 *
 * @param window how far back a run looks: alerts first found within this long before the run
 * @param maxAlertsPerRun most alerts one run reads; the rest stay unclaimed for the next run
 * @param sendsPerSecond most emails a second, to stay within the mail relay's limits
 * @param from the sender address
 * @param appUrl the address users sign in at, named in the email; blank = not named
 * @param dynamodbEndpoint blank = AWS; {@code http://localhost:8000} for DynamoDB Local
 * @param alertsTable the Alerts table
 * @param createTables create the Alerts table (and its index) if missing (local and test only)
 */
@ConfigurationProperties("credit.notifier")
public record NotifierProperties(
        Duration window,
        Integer maxAlertsPerRun,
        Double sendsPerSecond,
        String from,
        String appUrl,
        String dynamodbEndpoint,
        String region,
        Duration dynamodbTimeout,
        String alertsTable,
        Boolean createTables) {

    public NotifierProperties {
        window = window == null ? Duration.ofHours(24) : window;
        maxAlertsPerRun = maxAlertsPerRun == null ? 10_000 : maxAlertsPerRun;
        sendsPerSecond = sendsPerSecond == null ? 10.0 : sendsPerSecond;
        from = from == null || from.isBlank() ? "alerts@creditagent.local" : from.strip();
        appUrl = appUrl == null ? "" : appUrl.strip();
        region = region == null || region.isBlank() ? "us-east-1" : region;
        dynamodbTimeout = dynamodbTimeout == null ? Duration.ofSeconds(5) : dynamodbTimeout;
        alertsTable = alertsTable == null || alertsTable.isBlank() ? "Alerts" : alertsTable;
        createTables = createTables != null && createTables;
        if (!window.isPositive() || maxAlertsPerRun < 1 || sendsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "credit.notifier: window positive, max-alerts-per-run at least 1, sends-per-second above 0");
        }
    }
}
