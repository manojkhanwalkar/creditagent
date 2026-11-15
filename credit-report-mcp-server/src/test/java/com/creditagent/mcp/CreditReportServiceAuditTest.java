package com.creditagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.creditagent.common.AuditLog;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every read of a report is an audit record with the caller kind and a keyed reference, never the SSN. */
class CreditReportServiceAuditTest {

    final InMemoryCreditReportStore store = new InMemoryCreditReportStore();
    final AuditLog audit = mock(AuditLog.class);
    final Ssn ada = Ssn.parse("123-45-6789");

    @Test
    void foundAndNotFoundReadsAreRecordedPerCallerKind() {
        store.replaceAll(ada, Map.of(Bureau.EQUIFAX, "{}"));
        CreditReportService service = new CreditReportService(store, reports -> "{\"merged\":true}", audit);

        assertThat(service.findNormalizedReport(ada, CreditReportService.Via.RESOURCE_SERVICE)).contains("{\"merged\":true}");
        assertThat(service.findNormalizedReport(Ssn.parse("234-56-7890"), CreditReportService.Via.TOOL)).isEmpty();
        assertThat(service.findNormalizedReport(ada, CreditReportService.Via.RESOURCE_HOST)).isPresent();

        verify(audit).record(AuditLog.Event.REPORT_READ, "service:mcp-resource", "ssnref:ref-6789", "FOUND");
        verify(audit).record(AuditLog.Event.REPORT_READ, "mcp-host:tool", "ssnref:ref-7890", "NOT_FOUND");
        verify(audit).record(AuditLog.Event.REPORT_READ, "mcp-host:resource", "ssnref:ref-6789", "FOUND");
    }

    @Test
    void aFailedReadIsRecordedAndStillThrown() {
        store.replaceAll(ada, Map.of(Bureau.EQUIFAX, "{}"));
        CreditReportService service = new CreditReportService(store, reports -> {
            throw new IllegalStateException("NRG down");
        }, audit);

        assertThatThrownBy(() -> service.findNormalizedReport(ada, CreditReportService.Via.TOOL)).isInstanceOf(IllegalStateException.class);
        verify(audit).record(AuditLog.Event.REPORT_READ, "mcp-host:tool", "ssnref:ref-6789", "FAILED");
    }
}
