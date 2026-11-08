package com.creditagent.score;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic scorecard modelled on the publicly documented structure of the FICO Score, the
 * model most US lenders use with Equifax, Experian and TransUnion data.
 *
 * <p>FICO publishes the five categories and their weights for the general population
 * (myfico.com, "What's in my FICO Scores"); it does not publish the attribute bins or point values.
 * The bins below follow FICO's and the CFPB's public guidance (for example "keep utilisation low",
 * "a 90-day late hurts more than a 30-day late", "inquiries count for 12 months") and are tuned so a
 * clean, seasoned file scores in the high 700s to 800s and a file with serious derogatory marks
 * falls below 600.
 *
 * <ol>
 *   <li>Payment history, 35%: late payments by severity, charge-offs, collections, public records.
 *   <li>Amounts owed, 30%: revolving utilisation (total and per card), instalment pay-down, number
 *       of accounts carrying a balance.
 *   <li>Length of credit history, 15%: oldest account, average age, newest account.
 *   <li>New credit, 10%: hard inquiries in the last 12 months, accounts opened in the last 12 months.
 *   <li>Credit mix, 10%: revolving and instalment credit, with a bonus for a mortgage.
 * </ol>
 *
 * <p>Each category yields a sub-score from 0.0 to 1.0. The weighted sum maps linearly onto the
 * 300–850 range: {@code score = 300 + 550 × Σ weight × subScore}.
 *
 * <p>Like FICO, the model segments files into scorecards. A file with a major derogatory mark
 * (90+ days late, charge-off, collection or public record) is scored with weights that put 55% on
 * payment history, so a seriously delinquent file cannot score well on its other habits. A thin or
 * young file (under three accounts, or oldest account under two years) puts 25% on length of history.
 *
 * <p>Limits of the input data: the report carries late-payment counts but not their dates, so the
 * model cannot age delinquencies the way a bureau does and treats every reported late payment as
 * still within the seven-year reporting window. A real FICO Score also refuses to score a file with
 * no account open for at least six months; this service always returns a score, so an empty file
 * gets the conservative result described in {@link #breakdown(CreditReport)}.
 *
 * <p>Reports arrive as untrusted JSON, so the calculator first {@linkplain #normalize(CreditReport)
 * normalises} them rather than failing: negative counts, amounts and ages count as zero, an account
 * with no status counts as open, and an account with no type counts towards the number and age of
 * accounts and towards payment history, but not towards utilisation, instalment pay-down or mix.
 * Amounts are summed as doubles, so very large values cannot overflow.
 */
public class FactorBasedScoreCalculator implements ScoreCalculator {

    static final double PAYMENT_HISTORY_WEIGHT = 0.35;
    static final double AMOUNTS_OWED_WEIGHT = 0.30;
    static final double HISTORY_LENGTH_WEIGHT = 0.15;
    static final double NEW_CREDIT_WEIGHT = 0.10;
    static final double CREDIT_MIX_WEIGHT = 0.10;

    /** Category weights of one scorecard. Each scorecard's weights sum to 1. */
    public record Weights(double paymentHistory, double amountsOwed, double historyLength, double newCredit, double creditMix) {}

    /** FICO's published general-population weights, used for files without major derogatory marks. */
    static final Weights CLEAN_FILE = new Weights(
            PAYMENT_HISTORY_WEIGHT, AMOUNTS_OWED_WEIGHT, HISTORY_LENGTH_WEIGHT, NEW_CREDIT_WEIGHT, CREDIT_MIX_WEIGHT);

    /**
     * Weights for files with a major derogatory mark. FICO segments consumers into separate
     * scorecards and says the category weights vary by group; on a "dirty" file the delinquency
     * itself dominates, so good behaviour elsewhere cannot offset it.
     */
    static final Weights DEROGATORY_FILE = new Weights(0.55, 0.25, 0.10, 0.05, 0.05);

    /**
     * Weights for thin or young files (fewer than {@value #THIN_FILE_ACCOUNTS} accounts, or oldest
     * account under {@value #YOUNG_FILE_MONTHS} months). With little history to judge, its length
     * carries more weight, so a new borrower cannot reach the top of the range quickly.
     */
    static final Weights THIN_FILE = new Weights(0.30, 0.25, 0.25, 0.10, 0.10);

    static final int THIN_FILE_ACCOUNTS = 3;
    static final int YOUNG_FILE_MONTHS = 24;

    // Payment history: penalty units per item; sub-score = e^(-total). Diminishing returns mean a
    // first late payment costs more points than each additional one, as FICO describes.
    static final double LATE_30_PENALTY = 0.25;
    static final double LATE_60_PENALTY = 0.40;
    static final double LATE_90_PENALTY = 0.60;
    static final double CHARGE_OFF_PENALTY = 0.80;
    static final double COLLECTION_PENALTY = 0.60;
    static final double PUBLIC_RECORD_PENALTY = 1.00;

    static final int RECENT_MONTHS = 12;

    @Override
    public CreditScore calculate(CreditReport report) {
        return CreditScore.of(breakdown(report).score());
    }

    /** Category sub-scores and the resulting score, for tests and explanations. */
    public ScoreBreakdown breakdown(CreditReport report) {
        Objects.requireNonNull(report, "report");
        report = normalize(report);
        double payment = paymentHistory(report);
        double amounts = amountsOwed(report);
        double length = historyLength(report);
        double newCredit = newCredit(report);
        double mix = creditMix(report);
        Scorecard scorecard = scorecardFor(report);
        Weights w = scorecard.weights();
        double weighted = w.paymentHistory() * payment
                + w.amountsOwed() * amounts
                + w.historyLength() * length
                + w.newCredit() * newCredit
                + w.creditMix() * mix;
        int range = CreditScore.MAX - CreditScore.MIN;
        int score = (int) Math.round(CreditScore.MIN + range * weighted);
        score = Math.clamp(score, CreditScore.MIN, CreditScore.MAX);
        return new ScoreBreakdown(scorecard, payment, amounts, length, newCredit, mix, score);
    }

    /**
     * Clamps every count, amount and age to zero or more and gives an account without a status the
     * status {@link AccountStatus#OPEN}. The account type is kept as is, including {@code null}.
     */
    static CreditReport normalize(CreditReport report) {
        List<CreditAccount> accounts = report.accounts().stream().map(FactorBasedScoreCalculator::normalize).toList();
        return new CreditReport(
                report.reportId(),
                report.ssnMasked(),
                report.fullName(),
                report.generatedAt(),
                accounts,
                Math.max(0, report.hardInquiriesLast12Months()),
                Math.max(0, report.collections()),
                Math.max(0, report.publicRecords()));
    }

    static CreditAccount normalize(CreditAccount account) {
        return new CreditAccount(
                account.type(),
                account.creditor(),
                account.status() == null ? AccountStatus.OPEN : account.status(),
                Math.max(0, account.balance()),
                Math.max(0, account.creditLimit()),
                Math.max(0, account.monthsOpen()),
                Math.max(0, account.latePayments30()),
                Math.max(0, account.latePayments60()),
                Math.max(0, account.latePayments90()));
    }

    /** Derogatory segmentation takes precedence over thin-file segmentation. */
    static Scorecard scorecardFor(CreditReport report) {
        if (hasMajorDerogatory(report)) {
            return Scorecard.DEROGATORY_FILE;
        }
        if (!report.accounts().isEmpty()
                && (report.accounts().size() < THIN_FILE_ACCOUNTS || report.oldestAccountMonths() < YOUNG_FILE_MONTHS)) {
            return Scorecard.THIN_FILE;
        }
        return Scorecard.CLEAN_FILE;
    }

    /** A 90-day-or-worse late payment, a charge-off, a collection or a public record. */
    static boolean hasMajorDerogatory(CreditReport report) {
        return report.collections() > 0
                || report.publicRecords() > 0
                || report.accounts().stream()
                        .anyMatch(a -> a.latePayments90() > 0 || a.status() == AccountStatus.CHARGED_OFF);
    }

    // ---- 35% payment history -------------------------------------------------------------------

    static double paymentHistory(CreditReport report) {
        double penalty = 0;
        for (CreditAccount account : report.accounts()) {
            penalty += LATE_30_PENALTY * account.latePayments30()
                    + LATE_60_PENALTY * account.latePayments60()
                    + LATE_90_PENALTY * account.latePayments90();
            if (account.status() == AccountStatus.CHARGED_OFF) {
                penalty += CHARGE_OFF_PENALTY;
            }
        }
        penalty += COLLECTION_PENALTY * report.collections();
        penalty += PUBLIC_RECORD_PENALTY * report.publicRecords();
        return Math.exp(-penalty);
    }

    // ---- 30% amounts owed ----------------------------------------------------------------------

    static double amountsOwed(CreditReport report) {
        if (report.accounts().isEmpty()) {
            return 0.0;
        }
        return 0.70 * revolvingUtilizationScore(report)
                + 0.15 * installmentPaydownScore(report)
                + 0.15 * accountsWithBalanceScore(report);
    }

    /**
     * Blends total utilisation (80%) with the highest single-card utilisation (20%). Cards without a
     * positive limit are left out of both, since their utilisation is undefined.
     */
    static double revolvingUtilizationScore(CreditReport report) {
        List<CreditAccount> cards = owedRevolving(report).stream()
                .filter(c -> c.creditLimit() > 0)
                .toList();
        if (cards.isEmpty()) {
            return 0.70; // no revolving credit to show responsible use
        }
        double limit = cards.stream().mapToDouble(CreditAccount::creditLimit).sum();
        double balance = cards.stream().mapToDouble(CreditAccount::balance).sum();
        double highestCard = cards.stream()
                .mapToDouble(c -> (double) c.balance() / c.creditLimit())
                .max()
                .orElseThrow();
        return 0.80 * utilizationBand(balance / limit) + 0.20 * utilizationBand(highestCard);
    }

    /** Lower is better, but 1–9% scores slightly higher than 0% (FICO: some use beats none). */
    static double utilizationBand(double utilization) {
        if (utilization <= 0) return 0.90;
        if (utilization < 0.10) return 1.00;
        if (utilization < 0.30) return 0.85;
        if (utilization < 0.50) return 0.60;
        if (utilization < 0.75) return 0.40;
        if (utilization < 1.00) return 0.20;
        return 0.05;
    }

    /** Remaining balance as a share of the original amount across open instalment loans. */
    static double installmentPaydownScore(CreditReport report) {
        List<CreditAccount> loans = report.accounts().stream()
                .filter(a -> a.isOpen() && isInstallment(a.type()) && a.creditLimit() > 0)
                .toList();
        if (loans.isEmpty()) {
            return 0.80;
        }
        double remaining = loans.stream().mapToDouble(CreditAccount::balance).sum();
        double original = loans.stream().mapToDouble(CreditAccount::creditLimit).sum();
        double ratio = remaining / original;
        if (ratio <= 0.50) return 1.00;
        if (ratio <= 0.80) return 0.85;
        if (ratio <= 0.95) return 0.70;
        return 0.60;
    }

    static double accountsWithBalanceScore(CreditReport report) {
        long withBalance = report.accounts().stream()
                .filter(a -> a.status() != AccountStatus.CLOSED && a.balance() > 0)
                .count();
        if (withBalance <= 2) return 1.00;
        if (withBalance <= 4) return 0.85;
        if (withBalance <= 6) return 0.70;
        return 0.50;
    }

    // ---- 15% length of credit history ----------------------------------------------------------

    static double historyLength(CreditReport report) {
        List<CreditAccount> accounts = report.accounts();
        if (accounts.isEmpty()) {
            return 0.0;
        }
        int oldest = report.oldestAccountMonths();
        double average = accounts.stream().mapToInt(CreditAccount::monthsOpen).average().orElse(0);
        int newest = accounts.stream().mapToInt(CreditAccount::monthsOpen).min().orElse(0);
        return 0.45 * oldestAccountBand(oldest) + 0.40 * averageAgeBand(average) + 0.15 * newestAccountBand(newest);
    }

    static double oldestAccountBand(int months) {
        if (months < 12) return 0.20;
        if (months < 24) return 0.35;
        if (months < 60) return 0.55;
        if (months < 120) return 0.75;
        if (months < 240) return 0.90;
        return 1.00;
    }

    static double averageAgeBand(double months) {
        if (months < 12) return 0.20;
        if (months < 24) return 0.40;
        if (months < 48) return 0.60;
        if (months < 84) return 0.80;
        return 1.00;
    }

    static double newestAccountBand(int months) {
        if (months < 6) return 0.60;
        if (months < 12) return 0.80;
        return 1.00;
    }

    // ---- 10% new credit ------------------------------------------------------------------------

    static double newCredit(CreditReport report) {
        long recentlyOpened = report.accounts().stream().filter(a -> a.monthsOpen() < RECENT_MONTHS).count();
        return 0.60 * inquiryBand(report.hardInquiriesLast12Months()) + 0.40 * recentAccountsBand(recentlyOpened);
    }

    static double inquiryBand(int inquiries) {
        return switch (Math.max(0, inquiries)) {
            case 0 -> 1.00;
            case 1 -> 0.90;
            case 2 -> 0.80;
            case 3 -> 0.65;
            case 4 -> 0.50;
            case 5 -> 0.35;
            default -> 0.20;
        };
    }

    static double recentAccountsBand(long opened) {
        if (opened == 0) return 1.00;
        if (opened == 1) return 0.85;
        if (opened == 2) return 0.70;
        return 0.50;
    }

    // ---- 10% credit mix ------------------------------------------------------------------------

    static double creditMix(CreditReport report) {
        boolean revolving = report.accounts().stream().anyMatch(a -> a.type() == AccountType.CREDIT_CARD);
        boolean installment = report.accounts().stream().anyMatch(a -> isInstallment(a.type()));
        boolean mortgage = report.accounts().stream().anyMatch(a -> a.type() == AccountType.MORTGAGE);
        double mix;
        if (revolving && installment) {
            mix = 0.85;
        } else if (revolving || installment) {
            mix = 0.55;
        } else {
            return 0.0;
        }
        return Math.min(1.0, mix + (mortgage ? 0.15 : 0.0));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Open cards plus charged-off cards, whose unpaid balance is still owed. */
    private static List<CreditAccount> owedRevolving(CreditReport report) {
        return report.accounts().stream()
                .filter(a -> a.type() == AccountType.CREDIT_CARD && a.status() != AccountStatus.CLOSED)
                .toList();
    }

    /** Every known non-card type; an account without a type is neither revolving nor instalment. */
    private static boolean isInstallment(AccountType type) {
        return type != null && type != AccountType.CREDIT_CARD;
    }

    /** The population segment a report is scored in. */
    public enum Scorecard {
        CLEAN_FILE(FactorBasedScoreCalculator.CLEAN_FILE),
        DEROGATORY_FILE(FactorBasedScoreCalculator.DEROGATORY_FILE),
        THIN_FILE(FactorBasedScoreCalculator.THIN_FILE);

        private final Weights weights;

        Scorecard(Weights weights) {
            this.weights = weights;
        }

        public Weights weights() {
            return weights;
        }
    }

    /**
     * Sub-scores (each 0.0–1.0) for the five categories, and the final 300–850 score.
     *
     * <p>An empty file scores 0 for amounts owed, length and mix, and full marks for payment
     * history and new credit (nothing negative reported), which gives 548.
     */
    public record ScoreBreakdown(
            Scorecard scorecard,
            double paymentHistory,
            double amountsOwed,
            double historyLength,
            double newCredit,
            double creditMix,
            int score) {}
}
