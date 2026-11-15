package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creditagent.common.AuditLog;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportRetentionJobTest {

    static final Instant NOW = Instant.parse("2026-11-14T03:30:00Z");

    @Test
    void deletesOnlyReportsPastRetention() {
        InMemoryCreditReportStore store = new InMemoryCreditReportStore();
        store.clock = Clock.fixed(NOW.minus(Duration.ofDays(800)), ZoneOffset.UTC);
        store.replaceAll(Ssn.parse("123-45-6789"), Map.of(Bureau.EQUIFAX, "{}", Bureau.EXPERIAN, "{}"));
        store.clock = Clock.fixed(NOW.minus(Duration.ofDays(10)), ZoneOffset.UTC);
        store.replaceAll(Ssn.parse("234-56-7890"), Map.of(Bureau.EQUIFAX, "{}"));
        AuditLog audit = mock(AuditLog.class);

        new ReportRetentionJob(store, new GeneratorProperties(List.of()), audit, Clock.fixed(NOW, ZoneOffset.UTC)).run();

        assertThat(store.findAll(Ssn.parse("123-45-6789"))).isEmpty();
        assertThat(store.findAll(Ssn.parse("234-56-7890"))).hasSize(1);
        verify(audit).record(AuditLog.Event.REPORTS_PURGED, "job:report-retention", "-", "OK", Map.of("rows", 2));
    }

    @Test
    void aFailedRunIsAuditedAndDoesNotThrow() {
        CreditReportStore store = mock(CreditReportStore.class);
        when(store.purgeOlderThan(any(), anyInt())).thenThrow(new IllegalStateException("boom"));
        AuditLog audit = mock(AuditLog.class);
        GeneratorProperties properties = new GeneratorProperties(List.of(), "t", Duration.ofDays(30), 5);

        new ReportRetentionJob(store, properties, audit, Clock.fixed(NOW, ZoneOffset.UTC)).run();

        verify(store).purgeOlderThan(eq(NOW.minus(Duration.ofDays(30))), eq(5));
        verify(audit).record(AuditLog.Event.REPORTS_PURGED, "job:report-retention", "-", "FAILED");
    }

    @Test
    void propertiesHaveDefaultsRefuseNonsenseAndRedactTheToken() {
        GeneratorProperties defaults = new GeneratorProperties(null, null, null, null);
        assertThat(defaults.retention()).isEqualTo(Duration.ofDays(730));
        assertThat(defaults.purgeMaxPerRun()).isEqualTo(10_000);
        assertThat(defaults.serviceToken()).isEmpty();
        assertThat(new GeneratorProperties(List.of(), "secret-token", null, null).toString()).doesNotContain("secret-token");
        assertThatThrownBy(() -> new GeneratorProperties(null, null, Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeneratorProperties(null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
