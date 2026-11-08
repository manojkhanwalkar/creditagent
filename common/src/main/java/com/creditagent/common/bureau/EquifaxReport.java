package com.creditagent.common.bureau;

import java.util.List;

/**
 * Equifax consumer credit file, modelled on the segments Equifax publishes for its consumer
 * report (subject, trades, inquiries, collections, public records).
 *
 * <p>Format assumptions (Equifax does not publish a public JSON schema):
 * <ul>
 *   <li>Dates are {@code MM/dd/yyyy}; {@code dateOpened} is {@code MM/yyyy}.
 *   <li>Each trade carries the classic "manner of payment" rate: portfolio letter plus digit,
 *       e.g. {@code R1} revolving paid as agreed, {@code I9} instalment charged off.
 *   <li>Account type is the Metro 2 code (18 credit card, 00 auto, 26 mortgage, 12 education, 01 unsecured).
 *   <li>Amounts are whole US dollars.
 * </ul>
 */
public record EquifaxReport(
        String reportNumber,
        String dateOfReport,
        Subject subject,
        List<Trade> trades,
        List<Inquiry> inquiries,
        int collectionItems,
        int publicRecordItems) {

    public EquifaxReport {
        trades = trades == null ? List.of() : List.copyOf(trades);
        inquiries = inquiries == null ? List.of() : List.copyOf(inquiries);
    }

    /** @param ssnLast4 last four digits of the SSN only */
    public record Subject(String firstName, String lastName, String ssnLast4) {}

    /**
     * @param accountNumber masked, last four digits visible
     * @param portfolioType {@code R} revolving, {@code I} instalment, {@code M} mortgage
     * @param highCredit original amount (instalment) or highest balance (revolving)
     * @param rate manner-of-payment rate, e.g. {@code R1}
     * @param closed whether the consumer or grantor closed the account
     */
    public record Trade(
            String memberName,
            String accountNumber,
            String portfolioType,
            String accountType,
            String dateOpened,
            String dateReported,
            long highCredit,
            long creditLimit,
            long balance,
            String rate,
            int times30DaysLate,
            int times60DaysLate,
            int times90DaysLate,
            boolean closed) {}

    public record Inquiry(String memberName, String inquiryDate) {}
}
