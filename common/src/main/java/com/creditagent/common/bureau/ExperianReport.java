package com.creditagent.common.bureau;

import java.util.List;

/**
 * Experian credit profile, modelled on the Metro 2 fields Experian reports for each tradeline.
 *
 * <p>Format assumptions (Experian does not publish a public JSON schema):
 * <ul>
 *   <li>Dates are {@code yyyyMMdd}. Amounts are strings of whole US dollars, as in Experian's
 *       fixed-width heritage.
 *   <li>{@code accountTypeCode} is the Metro 2 account type (18, 00, 26, 12, 01).
 *   <li>{@code statusCode} is the Metro 2 account status: {@code 11} open/current, {@code 13}
 *       closed/paid, {@code 97} charged off.
 *   <li>{@code paymentProfile} is the payment history grid, newest month first, up to 84 months:
 *       {@code 0} current, {@code 1} 30 days late, {@code 2} 60, {@code 3} 90 or more.
 *   <li>Subscriber names are upper case; the consumer name is {@code LAST, FIRST}.
 * </ul>
 */
public record ExperianReport(
        String reportId,
        String reportDate,
        Consumer consumer,
        List<Tradeline> tradelines,
        List<Inquiry> inquiries,
        Summary summary) {

    public ExperianReport {
        tradelines = tradelines == null ? List.of() : List.copyOf(tradelines);
        inquiries = inquiries == null ? List.of() : List.copyOf(inquiries);
    }

    /** @param ssn masked, e.g. {@code XXXXX6789} */
    public record Consumer(String name, String ssn) {}

    /**
     * @param amount1 credit limit (revolving) or original loan amount (instalment)
     * @param balanceDate date the balance was reported
     */
    public record Tradeline(
            String subscriberName,
            String accountNumber,
            String accountTypeCode,
            String openDate,
            String balanceDate,
            String balanceAmount,
            String amount1,
            String statusCode,
            String paymentProfile) {}

    public record Inquiry(String subscriberName, String date) {}

    public record Summary(int collectionCount, int publicRecordCount) {}
}
