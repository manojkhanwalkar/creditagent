package com.creditagent.alerts;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs continuously: every {@code credit.monitor.interval} it checks every watched address at every
 * known location and records each new finding in the Alerts table. A finding already recorded is not
 * written again (the table's conditional put), so cycles can repeat freely. One failed cycle is logged
 * and the next one starts on schedule. Logs carry counts only, never addresses.
 */
public class DarkWebMonitor {

    private static final Logger log = LoggerFactory.getLogger(DarkWebMonitor.class);

    /** What one cycle did. */
    public record Cycle(int checked, int found, int recorded) {}

    private final WatchList.Source watchList;
    private final DarkWebSource source;
    private final AlertRepository alerts;
    private final MonitorProperties properties;
    private final Clock clock;

    public DarkWebMonitor(WatchList.Source watchList, DarkWebSource source, AlertRepository alerts,
            MonitorProperties properties, Clock clock) {
        this.watchList = Objects.requireNonNull(watchList);
        this.source = Objects.requireNonNull(source);
        this.alerts = Objects.requireNonNull(alerts);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString = "${credit.monitor.interval:60s}", initialDelayString = "${credit.monitor.initial-delay:10s}")
    public void scheduled() {
        try {
            Cycle cycle = cycle();
            log.info("Dark web scan: {} addresses checked at {} locations, {} found, {} new",
                    cycle.checked(), properties.locations().size(), cycle.found(), cycle.recorded());
        } catch (RuntimeException e) {
            log.warn("Dark web scan failed; the next one runs on schedule: {}", e.getClass().getSimpleName());
        }
    }

    /** One pass over every watched address and every location. */
    public Cycle cycle() {
        AtomicInteger found = new AtomicInteger();
        AtomicInteger recorded = new AtomicInteger();
        AtomicInteger checked = new AtomicInteger();
        Instant now = clock.instant();
        // The source caps rows scanned (users and other rows alike); count only the emails checked.
        watchList.forEachPage(properties.pageSize(), properties.maxEmailsPerCycle(), page -> {
            checked.addAndGet(page.size());
            for (String location : properties.locations()) {
                scan(location, page, now, found, recorded);
            }
        });
        return new Cycle(checked.get(), found.get(), recorded.get());
    }

    private void scan(String location, List<String> emails, Instant now, AtomicInteger found, AtomicInteger recorded) {
        for (String email : emails) {
            if (source.exposed(location, email)) {
                found.incrementAndGet();
                if (alerts.record(new Alert(email, location, now))) {
                    recorded.incrementAndGet();
                }
            }
        }
    }
}
