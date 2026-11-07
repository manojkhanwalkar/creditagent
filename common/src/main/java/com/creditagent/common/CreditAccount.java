package com.creditagent.common;

/**
 * One tradeline on a credit report. Amounts are whole US dollars.
 *
 * @param creditLimit credit limit for revolving accounts, original amount for instalment loans
 */
public record CreditAccount(
        AccountType type,
        String creditor,
        AccountStatus status,
        long balance,
        long creditLimit,
        int monthsOpen,
        int latePayments30,
        int latePayments60,
        int latePayments90) {

    public boolean isOpen() {
        return status == AccountStatus.OPEN;
    }

    public int totalLatePayments() {
        return latePayments30 + latePayments60 + latePayments90;
    }
}
