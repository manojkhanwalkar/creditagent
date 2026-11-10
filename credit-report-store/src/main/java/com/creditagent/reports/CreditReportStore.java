package com.creditagent.reports;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.util.Map;

/** Each bureau's report for each consumer. Production implementation: {@link DynamoDbCreditReportStore}. */
public interface CreditReportStore {

    /** Largest report JSON accepted, in UTF-8 bytes; DynamoDB items are at most 400 KB. */
    int MAX_REPORT_BYTES = 300 * 1024;

    /**
     * Replaces this consumer's whole set of bureau reports at once: a bureau absent from
     * {@code reports} ends up with no report. Readers ({@link #findAll}) never see a mix of two sets.
     *
     * @param reports each bureau's report JSON
     */
    void replaceAll(Ssn ssn, Map<Bureau, String> reports);

    /** Each bureau's report JSON for this consumer; bureaus without one are absent. */
    Map<Bureau, String> findAll(Ssn ssn);
}
