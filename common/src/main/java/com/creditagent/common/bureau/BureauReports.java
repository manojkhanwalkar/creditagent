package com.creditagent.common.bureau;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One consumer's reports from the three bureaus. Any of them may be missing (null): not every
 * consumer has a file at every bureau.
 *
 * <p>This is also the request body of the Normalized Report Generator ({@code POST
 * /api/v1/normalized-reports}): {@code {"equifax":{…},"experian":{…},"transunion":{…}}}.
 */
public record BureauReports(EquifaxReport equifax, ExperianReport experian, TransUnionReport transunion) {

    public boolean isEmpty() {
        return equifax == null && experian == null && transunion == null;
    }

    /**
     * The id of the report these files normalize to. It is derived from the bureau report ids, so the
     * generator can return it up front and every later read of the same files yields the same id.
     */
    public String normalizedReportId() {
        String ids = (equifax == null ? "" : equifax.reportNumber()) + "|"
                + (experian == null ? "" : experian.reportId()) + "|"
                + (transunion == null ? "" : transunion.fileNumber());
        return UUID.nameUUIDFromBytes(ids.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
