package com.creditagent.common.bureau;

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
}
