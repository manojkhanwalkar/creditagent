package com.creditagent.generator;

import com.creditagent.common.AuditLog;
import com.creditagent.common.SafeLogging;
import com.creditagent.reports.CreditReportStore;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes stored bureau reports not updated within {@code credit.generator.retention} (default two
 * years), once a day. This also covers accounts deleted for inactivity: the web server keeps only the
 * SSN's keyed hash under a different key, so it cannot name their reports, and a report nobody has
 * refreshed for that long belongs to nobody active. A failed run is logged (class only) and the next
 * day's run carries on; deletes are idempotent.
 */
@Component
class ReportRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ReportRetentionJob.class);

    private final CreditReportStore store;
    private final GeneratorProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    @Autowired
    ReportRetentionJob(CreditReportStore store, GeneratorProperties properties, AuditLog audit) {
        this(store, properties, audit, Clock.systemUTC());
    }

    ReportRetentionJob(CreditReportStore store, GeneratorProperties properties, AuditLog audit, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(cron = "${credit.generator.retention-cron:0 30 3 * * *}", zone = "UTC")
    void run() {
        try {
            int deleted = store.purgeOlderThan(clock.instant().minus(properties.retention()), properties.purgeMaxPerRun());
            audit.record(AuditLog.Event.REPORTS_PURGED, "job:report-retention", "-", "OK", Map.of("rows", deleted));
            log.info("Report retention: {} rows past {} deleted", deleted, properties.retention());
        } catch (RuntimeException e) {
            audit.record(AuditLog.Event.REPORTS_PURGED, "job:report-retention", "-", "FAILED");
            log.warn("Report retention failed: {}", SafeLogging.describe(e));
        }
    }
}
