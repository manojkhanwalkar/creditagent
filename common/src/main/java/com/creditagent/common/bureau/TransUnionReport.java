package com.creditagent.common.bureau;

import java.util.List;

/**
 * TransUnion credit report, modelled on TransUnion's trade segment (industry code, portfolio
 * type, account rating and historical delinquency counters).
 *
 * <p>Format assumptions (TransUnion does not publish a public JSON schema):
 * <ul>
 *   <li>Dates are ISO-8601 ({@code yyyy-MM-dd}). Amounts are whole US dollars.
 *   <li>{@code industryCode}: {@code BC} bank card, {@code FA} auto finance, {@code FM} mortgage,
 *       {@code ES} education/student, {@code FP} personal finance.
 *   <li>{@code accountRating} is the two-digit rating: {@code 01} paid as agreed, {@code 09}
 *       charged off. A closed account carries the remark {@code CLOSED}.
 * </ul>
 */
public record TransUnionReport(
        String fileNumber,
        String reportDate,
        Name name,
        String ssnMasked,
        List<Trade> trades,
        List<Inquiry> inquiries,
        int collectionsCount,
        int publicRecordsCount) {

    public TransUnionReport {
        trades = trades == null ? List.of() : List.copyOf(trades);
        inquiries = inquiries == null ? List.of() : List.copyOf(inquiries);
    }

    public record Name(String first, String last) {}

    /**
     * @param portfolioType {@code revolving}, {@code installment} or {@code mortgage}
     * @param creditLimit null for instalment and mortgage trades
     * @param highCredit original amount for instalment and mortgage trades
     * @param dateEffective date the trade was last updated
     */
    public record Trade(
            String subscriberName,
            String accountNumber,
            String industryCode,
            String portfolioType,
            String dateOpened,
            String dateEffective,
            long currentBalance,
            Long creditLimit,
            Long highCredit,
            String accountRating,
            HistoricalCounters historicalCounters,
            String remark) {}

    public record HistoricalCounters(int late30, int late60, int late90) {}

    public record Inquiry(String subscriberName, String inquiryDate) {}
}
