package com.creditagent.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

class DarkWebMonitorTest {

    static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    /** Same contract as the DynamoDB repository: one alert per email and location. */
    static final class InMemoryAlerts implements AlertRepository {
        final Map<String, Alert> byKey = new ConcurrentHashMap<>();

        @Override
        public boolean record(Alert alert) {
            return byKey.putIfAbsent(alert.email() + "|" + alert.location(), alert) == null;
        }

        @Override
        public List<Alert> find(String email) {
            return byKey.values().stream().filter(a -> a.email().equals(Alert.normalise(email))).toList();
        }

        @Override
        public List<Alert> unnotifiedBetween(java.time.Instant from, java.time.Instant to, int max) {
            throw new UnsupportedOperationException("the monitor never reads for notifications");
        }

        @Override
        public boolean claimNotification(Alert alert, java.time.Instant runAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseNotification(Alert alert, java.time.Instant runAt) {
            throw new UnsupportedOperationException();
        }
    }

    static MonitorProperties properties(List<String> exposed, double rate, int max) {
        return new MonitorProperties(Duration.ofSeconds(60), List.of("loc-a", "loc-b"), exposed, rate, max, 2,
                null, null, null, null, null, null);
    }

    static WatchList.Source fixed(List<String> emails) {
        return (pageSize, max, page) -> {
            List<String> capped = emails.subList(0, Math.min(max, emails.size()));
            for (int i = 0; i < capped.size(); i += pageSize) {
                page.accept(capped.subList(i, Math.min(i + pageSize, capped.size())));
            }
            return capped.size();
        };
    }

    @Test
    void aCycleRecordsEachExposureOnceAtEachLocation() {
        InMemoryAlerts alerts = new InMemoryAlerts();
        MonitorProperties props = properties(List.of("Leaked@Example.com"), 0, 100);
        DarkWebMonitor monitor = new DarkWebMonitor(fixed(List.of("leaked@example.com", "safe@example.com", "x@example.com")),
                DarkWebSource.placeholder(Set.copyOf(props.placeholderExposed()), 0), alerts, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(monitor.cycle()).isEqualTo(new DarkWebMonitor.Cycle(3, 2, 2));
        assertThat(alerts.find("leaked@example.com")).extracting(Alert::location).containsExactlyInAnyOrder("loc-a", "loc-b");
        assertThat(alerts.find("safe@example.com")).isEmpty();
        // Found again next cycle, but not written again.
        assertThat(monitor.cycle()).isEqualTo(new DarkWebMonitor.Cycle(3, 2, 0));
    }

    @Test
    void theCycleIsCappedAndAFailedCycleDoesNotStopTheLoop() {
        InMemoryAlerts alerts = new InMemoryAlerts();
        DarkWebMonitor capped = new DarkWebMonitor(fixed(List.of("a@x.io", "b@x.io", "c@x.io")),
                (location, email) -> true, alerts, properties(List.of(), 0, 2), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(capped.cycle().checked()).isEqualTo(2);

        DarkWebMonitor broken = new DarkWebMonitor((p, m, page) -> { throw new IllegalStateException("DynamoDB down"); },
                (location, email) -> true, alerts, properties(List.of(), 0, 2), Clock.fixed(NOW, ZoneOffset.UTC));
        broken.scheduled(); // logged, not thrown
        capped.scheduled();
    }

    @Test
    void onlyEmailsCountAsCheckedNotOtherRowsTheScanRead() {
        // The users table also holds SSN-claim rows: the scan reads 4 rows but finds 2 emails.
        WatchList.Source twoUsersFourRows = (pageSize, max, page) -> {
            page.accept(List.of("a@x.io", "b@x.io"));
            return 4;
        };
        DarkWebMonitor monitor = new DarkWebMonitor(twoUsersFourRows, (location, email) -> false, new InMemoryAlerts(),
                properties(List.of(), 0, 100), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(monitor.cycle().checked()).isEqualTo(2);
    }

    @Test
    void thePlaceholderIsDeterministicAndRoughlyAtItsRate() {
        DarkWebSource source = DarkWebSource.placeholder(Set.of(), 0.2);
        int found = 0;
        for (int i = 0; i < 2000; i++) {
            String email = "user" + i + "@example.com";
            boolean first = source.exposed("loc-a", email);
            assertThat(source.exposed("loc-a", email)).isEqualTo(first);
            found += first ? 1 : 0;
        }
        assertThat(found).isBetween(300, 500);
        assertThat(DarkWebSource.fraction("anything")).isBetween(0.0, 1.0);
        assertThat(DarkWebSource.placeholder(Set.of(), 0).exposed("l", "e")).isFalse();
    }

    @Test
    void theWatchListReadsUserEmailsOnlyAndResumesWhereItStopped() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class, CALLS_REAL_METHODS);
        Map<String, AttributeValue> key = Map.of("userId", AttributeValue.fromS("bob"));
        ScanResponse first = ScanResponse.builder().scannedCount(2)
                .items(Map.of("email", AttributeValue.fromS("Ada@Example.com")), Map.of("email", AttributeValue.fromS("bob@example.com")))
                .lastEvaluatedKey(key).build();
        ScanResponse second = ScanResponse.builder().scannedCount(1)
                .items(Map.of("email", AttributeValue.fromS("cy@example.com"))).build();
        List<ScanRequest> requests = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return requests.size() == 2 ? second : first;
        }).when(dynamo).scan(any(ScanRequest.class));

        WatchList.Source users = WatchList.users(dynamo, "credit-agent-users");
        List<String> seen = new ArrayList<>();
        assertThat(users.forEachPage(2, 2, seen::addAll)).isEqualTo(2);   // capped after the first page
        assertThat(seen).containsExactly("ada@example.com", "bob@example.com");
        assertThat(requests.getFirst().projectionExpression()).isEqualTo("#email");
        assertThat(requests.getFirst().filterExpression()).contains("attribute_exists(#pw)");

        seen.clear();
        assertThat(users.forEachPage(2, 10, seen::addAll)).isEqualTo(1);  // resumes after bob
        assertThat(requests.get(1).exclusiveStartKey()).isEqualTo(key);
        assertThat(seen).containsExactly("cy@example.com");

        users.forEachPage(2, 10, page -> { });                            // reached the end: starts over
        assertThat(requests.get(2).exclusiveStartKey()).isEmpty();
    }

    @Test
    void propertiesHaveDefaultsAndRefuseNonsense() {
        MonitorProperties defaults = new MonitorProperties(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(defaults.locations()).hasSize(3);
        assertThat(defaults.alertsTable()).isEqualTo("Alerts");
        assertThat(defaults.usersTable()).isEqualTo("credit-agent-users");
        assertThat(defaults.createTables()).isFalse();
        assertThat(new MonitorProperties(null, null, List.of(" A@B.co ", " "), null, null, null, null, null, null, null, null, null)
                .placeholderExposed()).containsExactly("a@b.co");
        assertThatThrownBy(() -> new MonitorProperties(Duration.ZERO, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitorProperties(null, null, null, 1.5, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
