package com.creditagent.mcp;

import com.creditagent.common.bureau.Bureau;
import java.util.Map;

/** Sends a consumer's bureau reports to the Normalized Report Generator (NRG). */
@FunctionalInterface
public interface ReportNormalizerClient {

    /**
     * @param bureauReports raw JSON per bureau; at least one entry
     * @return the normalized credit report as JSON
     */
    String normalize(Map<Bureau, String> bureauReports);
}
