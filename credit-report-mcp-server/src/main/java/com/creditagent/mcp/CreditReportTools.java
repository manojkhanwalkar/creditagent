package com.creditagent.mcp;

import com.creditagent.clients.ReportLockCheck;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools served through LangChain4j's {@code McpServer}. The same data is also available as the
 * {@code credit-report://ssn/{ssn}} resource, which is what this project's own clients use.
 *
 * <p>The tool is for generic MCP hosts, which are third parties to the consumer: it refuses a report
 * whose owner has locked it, and refuses when the lock cannot be checked (fail closed). The resource
 * is not checked here: the web server reads it only for the signed-in owner, and the Credit
 * Intelligence Agent checks the lock itself before reading.
 */
@Component
public class CreditReportTools {

    static final String NOT_FOUND = "No credit report found for that SSN.";
    static final String LOCKED = ReportLockCheck.LOCKED_MESSAGE;
    static final String LOCK_UNAVAILABLE = "The credit report cannot be released right now. Please try again later.";

    private final CreditReportService reports;
    private final ReportLockCheck locks;

    public CreditReportTools(CreditReportService reports, ReportLockCheck locks) {
        this.reports = reports;
        this.locks = locks;
    }

    @Tool(name = "get_credit_report", value = "Returns the normalized credit report (JSON), merged from all three bureaus, for a US Social Security Number, unless its owner has locked it.")
    public String getCreditReport(@P("SSN, formatted AAA-GG-SSSS or as 9 digits") String ssn) {
        Ssn parsed;
        try {
            parsed = Ssn.parse(ssn);
        } catch (InvalidSsnException e) {
            return e.getMessage();
        }
        return switch (locks.decide(parsed)) {
            case LOCKED -> LOCKED;
            case UNAVAILABLE -> LOCK_UNAVAILABLE;
            case RELEASE -> reports.findNormalizedReport(parsed).orElse(NOT_FOUND);
        };
    }
}
