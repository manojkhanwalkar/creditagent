package com.creditagent.alerts;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dark web monitor ({@code credit.monitor.*}).
 *
 * @param interval pause between scan cycles
 * @param locations the "known locations" a cycle checks (placeholder ids: nothing is contacted)
 * @param placeholderExposed email addresses the placeholder always reports as found (for demos)
 * @param placeholderRate share of other addresses the placeholder reports as found at each location,
 *     chosen deterministically, 0 to 1
 * @param maxEmailsPerCycle most addresses one cycle checks; the next cycle starts again from the top
 * @param pageSize addresses read from the users table at a time
 * @param dynamodbEndpoint blank = AWS; {@code http://localhost:8000} for DynamoDB Local
 * @param usersTable the web server's users table, read for addresses only
 * @param alertsTable the Alerts table this service writes
 * @param createTables create the Alerts table if missing (local and test only)
 */
@ConfigurationProperties("credit.monitor")
public record MonitorProperties(
        Duration interval,
        List<String> locations,
        List<String> placeholderExposed,
        Double placeholderRate,
        Integer maxEmailsPerCycle,
        Integer pageSize,
        String dynamodbEndpoint,
        String region,
        Duration dynamodbTimeout,
        String usersTable,
        String alertsTable,
        Boolean createTables) {

    public MonitorProperties {
        interval = interval == null ? Duration.ofMinutes(1) : interval;
        locations = locations == null || locations.isEmpty()
                ? List.of("paste-site-alpha", "breach-forum-beta", "marketplace-gamma") : List.copyOf(locations);
        placeholderExposed = placeholderExposed == null ? List.of()
                : placeholderExposed.stream().filter(e -> !e.isBlank()).map(Alert::normalise).toList();
        placeholderRate = placeholderRate == null ? 0.02 : placeholderRate;
        maxEmailsPerCycle = maxEmailsPerCycle == null ? 10_000 : maxEmailsPerCycle;
        pageSize = pageSize == null ? 100 : pageSize;
        region = region == null || region.isBlank() ? "us-east-1" : region;
        dynamodbTimeout = dynamodbTimeout == null ? Duration.ofSeconds(5) : dynamodbTimeout;
        usersTable = usersTable == null || usersTable.isBlank() ? "credit-agent-users" : usersTable;
        alertsTable = alertsTable == null || alertsTable.isBlank() ? "Alerts" : alertsTable;
        createTables = createTables != null && createTables;
        if (!interval.isPositive() || placeholderRate < 0 || placeholderRate > 1 || maxEmailsPerCycle < 1 || pageSize < 1
                || pageSize > 1000) {
            throw new IllegalArgumentException("credit.monitor: interval positive, placeholder-rate in [0, 1], "
                    + "max-emails-per-cycle at least 1, page-size 1 to 1000");
        }
    }
}
