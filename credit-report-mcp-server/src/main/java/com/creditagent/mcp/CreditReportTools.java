package com.creditagent.mcp;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools served through LangChain4j's {@code McpServer}. The same data is also available as the
 * {@code credit-report://ssn/{ssn}} resource, which is what this project's own clients use.
 */
@Component
public class CreditReportTools {

    static final String NOT_FOUND = "No credit report found for that SSN.";

    private final CreditFileRepository repository;

    public CreditReportTools(CreditFileRepository repository) {
        this.repository = repository;
    }

    @Tool(name = "get_credit_report", value = "Returns the credit report (JSON) for a US Social Security Number.")
    public String getCreditReport(@P("SSN, formatted AAA-GG-SSSS or as 9 digits") String ssn) {
        try {
            return repository.findReportJson(Ssn.parse(ssn)).orElse(NOT_FOUND);
        } catch (InvalidSsnException e) {
            return e.getMessage();
        }
    }
}
