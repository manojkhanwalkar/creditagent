package com.creditagent.notifier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The alert notifier ({@code credit.notifier.*}).
 *
 * @param lookback how far back a run looks for alerts no email has covered yet. Each daily run normally
 *     finds only the last 24 hours' alerts (the previous run claimed the rest); the longer lookback lets
 *     a missed, failed or capped run catch up instead of losing those alerts
 * @param maxAlertsPerRun most alerts one run reads; the rest stay unclaimed for the next run
 * @param sendsPerSecond most emails a second, to stay within the notification-service's limits
 * @param appUrl the address users sign in at, named in the email; blank = not named
 * @param dynamodbEndpoint blank = AWS; {@code http://localhost:8000} for DynamoDB Local
 * @param alertsTable the Alerts table
 * @param createTables create the Alerts and Subscriptions tables (and the index) if missing (local and test only)
 * @param subscriptionsTable the Subscriptions table: only ID Protection subscribers are emailed
 */
@ConfigurationProperties("credit.notifier")
public record NotifierProperties(
        Duration lookback,
        Integer maxAlertsPerRun,
        Double sendsPerSecond,
        String appUrl,
        String dynamodbEndpoint,
        String region,
        Duration dynamodbTimeout,
        String alertsTable,
        Boolean createTables,
        String subscriptionsTable) {

    public NotifierProperties {
        lookback = lookback == null ? Duration.ofDays(7) : lookback;
        maxAlertsPerRun = maxAlertsPerRun == null ? 10_000 : maxAlertsPerRun;
        sendsPerSecond = sendsPerSecond == null ? 10.0 : sendsPerSecond;
        appUrl = appUrl == null ? "" : appUrl.strip();
        region = region == null || region.isBlank() ? "us-east-1" : region;
        dynamodbTimeout = dynamodbTimeout == null ? Duration.ofSeconds(5) : dynamodbTimeout;
        alertsTable = alertsTable == null || alertsTable.isBlank() ? "Alerts" : alertsTable;
        createTables = createTables != null && createTables;
        subscriptionsTable = subscriptionsTable == null || subscriptionsTable.isBlank()
                ? "credit-agent-subscriptions" : subscriptionsTable;
        if (!lookback.isPositive() || maxAlertsPerRun < 1 || sendsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "credit.notifier: lookback positive, max-alerts-per-run at least 1, sends-per-second above 0");
        }
    }
}
