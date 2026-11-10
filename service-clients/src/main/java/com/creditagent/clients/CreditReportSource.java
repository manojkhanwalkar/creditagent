package com.creditagent.clients;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import java.util.Optional;

/** Where credit reports come from. Production implementation: {@link McpCreditReportSource}. */
public interface CreditReportSource {

    /**
     * @return the report, or empty if no bureau holds a report for this SSN
     * @throws ServiceUnavailableException if the source cannot be reached
     */
    Optional<CreditReport> findReport(Ssn ssn);
}
