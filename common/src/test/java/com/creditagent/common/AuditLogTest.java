package com.creditagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.creditagent.common.AuditLog.Event;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class AuditLogTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    void writesOneOrderedLineToTheSink() {
        Logger sink = mock(Logger.class);
        new AuditLog(sink, Clock.fixed(NOW, ZoneOffset.UTC))
                .record(Event.REPORT_READ, "user:ada", "user:ada", "OK", Map.of("via", "score"));
        verify(sink).info("event=REPORT_READ actor=user:ada subject=user:ada outcome=OK at=2026-11-14T10:00:00Z via=score");
    }

    @Test
    void withoutDetails() {
        Logger sink = mock(Logger.class);
        new AuditLog(sink, Clock.fixed(NOW, ZoneOffset.UTC)).record(Event.ACCOUNT_DELETED, "user:ada", "user:ada", "OK");
        verify(sink).info("event=ACCOUNT_DELETED actor=user:ada subject=user:ada outcome=OK at=2026-11-14T10:00:00Z");
    }

    @Test
    void newlinesAndSpacesCannotForgeASecondRecord() {
        String line = AuditLog.format(Event.REPORT_READ, "user:a\nevent=ACCOUNT_DELETED actor=x", "s", "OK", NOW, Map.of());
        assertThat(line).doesNotContain("\n").startsWith("event=REPORT_READ actor=user:a_event_ACCOUNT_DELETED_actor_x ");
    }

    @Test
    void detailsCannotOverrideTheFixedFields() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", "OK");
        details.put("count", 3);
        details.put("none", null);
        String line = AuditLog.format(Event.REPORTS_PURGED, "job:retention", "-", "FAILED", NOW, details);
        assertThat(line).isEqualTo("event=REPORTS_PURGED actor=job:retention subject=- outcome=FAILED at=2026-11-14T10:00:00Z count=3 none=-");
    }

    @Test
    void valuesAreCappedAndNonAsciiReplaced() {
        assertThat(AuditLog.clean("x".repeat(500))).hasSize(AuditLog.MAX_VALUE_LENGTH);
        assertThat(AuditLog.clean("Іvan é")).isEqualTo("_van__");
        assertThat(AuditLog.clean("")).isEqualTo("-");
        assertThat(AuditLog.clean(null)).isEqualTo("-");
        assertThat(AuditLog.format(Event.ALERTS_ERASED, null, null, null, NOW, null))
                .isEqualTo("event=ALERTS_ERASED actor=- subject=- outcome=- at=2026-11-14T10:00:00Z");
    }

    @Test
    void defaultInstanceUsesTheAuditLogger() {
        new AuditLog().record(Event.CONNECTOR_GRANT_REVOKED, "user:ada", "user:ada", "OK");
        assertThat(AuditLog.LOGGER).isEqualTo("audit");
    }
}
