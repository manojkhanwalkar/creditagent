package com.creditagent.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.ScoreBand;
import com.creditagent.score.FactorBasedScoreCalculator.ScoreBreakdown;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FactorBasedScoreCalculatorTest {

    final FactorBasedScoreCalculator calculator = new FactorBasedScoreCalculator();

    static CreditAccount card(long balance, long limit, int months) {
        return new CreditAccount(AccountType.CREDIT_CARD, "Northwind Bank", AccountStatus.OPEN, balance, limit, months, 0, 0, 0);
    }

    static CreditAccount loan(AccountType type, long balance, long original, int months) {
        return new CreditAccount(type, "Litware Lending", AccountStatus.OPEN, balance, original, months, 0, 0, 0);
    }

    static CreditReport report(List<CreditAccount> accounts, int inquiries, int collections, int publicRecords) {
        return new CreditReport("r", "***-**-6789", "A B", Instant.EPOCH, accounts, inquiries, collections, publicRecords);
    }

    /** Seasoned, clean file: low utilisation, cards and a mortgage, no inquiries. */
    static List<CreditAccount> pristineAccounts() {
        return List.of(
                card(300, 15_000, 300),
                card(0, 10_000, 180),
                loan(AccountType.MORTGAGE, 150_000, 400_000, 144),
                loan(AccountType.AUTO_LOAN, 6_000, 30_000, 48));
    }

    @Test
    void weightsMatchFicoPublishedCategoryWeights() {
        assertThat(FactorBasedScoreCalculator.PAYMENT_HISTORY_WEIGHT
                        + FactorBasedScoreCalculator.AMOUNTS_OWED_WEIGHT
                        + FactorBasedScoreCalculator.HISTORY_LENGTH_WEIGHT
                        + FactorBasedScoreCalculator.NEW_CREDIT_WEIGHT
                        + FactorBasedScoreCalculator.CREDIT_MIX_WEIGHT)
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void pristineFileScoresExceptional() {
        ScoreBreakdown b = calculator.breakdown(report(pristineAccounts(), 0, 0, 0));
        assertThat(b.score()).isBetween(800, 850);
        assertThat(b.paymentHistory()).isEqualTo(1.0);
        assertThat(calculator.calculate(report(pristineAccounts(), 0, 0, 0)).band()).isEqualTo(ScoreBand.GREEN);
    }

    @Test
    void latePaymentsHurtMoreTheMoreSevereTheyAre() {
        int clean = score(pristineAccounts());
        int late30 = score(withLate(1, 0, 0));
        int late60 = score(withLate(0, 1, 0));
        int late90 = score(withLate(0, 0, 1));
        assertThat(clean).isGreaterThan(late30);
        assertThat(late30).isGreaterThan(late60);
        assertThat(late60).isGreaterThan(late90);
        assertThat(clean - late30).as("a single 30-day late costs tens of points").isBetween(25, 80);
    }

    @Test
    void eachExtraLatePaymentCostsLessThanTheFirst() {
        int clean = score(pristineAccounts());
        int one = score(withLate(1, 0, 0));
        int two = score(withLate(2, 0, 0));
        assertThat(clean - one).isGreaterThan(one - two);
    }

    @Test
    void derogatoryMarksDropTheScoreBelowSixHundred() {
        List<CreditAccount> accounts = new ArrayList<>(withLate(0, 1, 2));
        accounts.add(new CreditAccount(AccountType.CREDIT_CARD, "Contoso Card", AccountStatus.CHARGED_OFF, 2_000, 2_000, 60, 0, 0, 1));
        int score = score(report(accounts, 2, 1, 1));
        assertThat(score).isLessThan(600);
        assertThat(score).isGreaterThanOrEqualTo(300);
    }

    @Test
    void utilizationBandsFollowPublicGuidance() {
        assertThat(FactorBasedScoreCalculator.utilizationBand(0.05)).isGreaterThan(FactorBasedScoreCalculator.utilizationBand(0.0));
        assertThat(FactorBasedScoreCalculator.utilizationBand(0.0)).isGreaterThan(FactorBasedScoreCalculator.utilizationBand(0.20));
        assertThat(FactorBasedScoreCalculator.utilizationBand(0.20)).isGreaterThan(FactorBasedScoreCalculator.utilizationBand(0.40));
        assertThat(FactorBasedScoreCalculator.utilizationBand(0.40)).isGreaterThan(FactorBasedScoreCalculator.utilizationBand(0.80));
        assertThat(FactorBasedScoreCalculator.utilizationBand(0.80)).isGreaterThan(FactorBasedScoreCalculator.utilizationBand(1.05));
    }

    @Test
    void maxedOutCardsLowerTheScore() {
        int low = score(report(List.of(card(500, 10_000, 120), loan(AccountType.AUTO_LOAN, 5_000, 20_000, 40)), 0, 0, 0));
        int high = score(report(List.of(card(9_800, 10_000, 120), loan(AccountType.AUTO_LOAN, 5_000, 20_000, 40)), 0, 0, 0));
        assertThat(low - high).isGreaterThan(50);
    }

    @Test
    void inquiriesAndNewAccountsCostAFewPointsEach() {
        int none = score(report(pristineAccounts(), 0, 0, 0));
        int some = score(report(pristineAccounts(), 3, 0, 0));
        assertThat(none - some).isBetween(5, 30);
    }

    @Test
    void thinYoungFileScoresLowerThanSeasonedFile() {
        int young = score(report(List.of(card(100, 1_000, 8)), 1, 0, 0));
        assertThat(young).isBetween(650, 720);
        assertThat(young).isLessThan(score(report(pristineAccounts(), 0, 0, 0)) - 100);
    }

    @Test
    void mixOfRevolvingAndInstallmentBeatsCardsOnly() {
        CreditReport cardsOnly = report(List.of(card(300, 15_000, 300)), 0, 0, 0);
        CreditReport mixed = report(List.of(card(300, 15_000, 300), loan(AccountType.STUDENT_LOAN, 4_000, 20_000, 300)), 0, 0, 0);
        assertThat(FactorBasedScoreCalculator.creditMix(mixed)).isGreaterThan(FactorBasedScoreCalculator.creditMix(cardsOnly));
    }

    @Test
    void emptyFileGetsConservativeScore() {
        assertThat(score(report(List.of(), 0, 0, 0))).isEqualTo(548);
    }

    @Test
    void scoreStaysInRangeForWorstCase() {
        List<CreditAccount> accounts = List.of(new CreditAccount(
                AccountType.CREDIT_CARD, "X", AccountStatus.CHARGED_OFF, 50_000, 1_000, 1, 9, 9, 9));
        assertThat(score(report(accounts, 20, 10, 5))).isBetween(300, 399);
    }

    @Test
    void everyScorecardsWeightsSumToOne() {
        for (FactorBasedScoreCalculator.Scorecard card : FactorBasedScoreCalculator.Scorecard.values()) {
            FactorBasedScoreCalculator.Weights w = card.weights();
            assertThat(w.paymentHistory() + w.amountsOwed() + w.historyLength() + w.newCredit() + w.creditMix())
                    .as(card.name())
                    .isCloseTo(1.0, within(1e-9));
        }
    }

    @Test
    void majorDerogatoryMarksSwitchToTheDerogatoryScorecard() {
        assertThat(calculator.breakdown(report(pristineAccounts(), 0, 0, 0)).scorecard())
                .isEqualTo(FactorBasedScoreCalculator.Scorecard.CLEAN_FILE);
        assertThat(calculator.breakdown(report(withLate(2, 1, 0), 0, 0, 0)).scorecard())
                .as("30/60-day lates stay on the clean scorecard")
                .isEqualTo(FactorBasedScoreCalculator.Scorecard.CLEAN_FILE);
        assertThat(calculator.breakdown(report(withLate(0, 0, 1), 0, 0, 0)).scorecard())
                .isEqualTo(FactorBasedScoreCalculator.Scorecard.DEROGATORY_FILE);
        assertThat(calculator.breakdown(report(pristineAccounts(), 0, 1, 0)).scorecard())
                .isEqualTo(FactorBasedScoreCalculator.Scorecard.DEROGATORY_FILE);
    }

    @Test
    void thinOrYoungFilesUseTheThinFileScorecard() {
        assertThat(calculator.breakdown(report(List.of(card(100, 1_000, 8)), 1, 0, 0)).scorecard())
                .isEqualTo(FactorBasedScoreCalculator.Scorecard.THIN_FILE);
        assertThat(calculator.breakdown(report(List.of(card(100, 1_000, 8)), 0, 1, 0)).scorecard())
                .as("derogatory takes precedence")
                .isEqualTo(FactorBasedScoreCalculator.Scorecard.DEROGATORY_FILE);
    }

    @Test
    void aSingleCollectionOnAnOtherwisePerfectFileCostsOverAHundredPoints() {
        int clean = score(report(pristineAccounts(), 0, 0, 0));
        int collection = score(report(pristineAccounts(), 0, 1, 0));
        assertThat(clean - collection).isGreaterThan(100);
        assertThat(collection).isBetween(600, 720);
    }

    @Test
    void isDeterministic() {
        CreditReport r = report(pristineAccounts(), 2, 0, 0);
        assertThat(calculator.calculate(r)).isEqualTo(calculator.calculate(r));
    }

    @Test
    void requiresAReport() {
        assertThatThrownBy(() -> calculator.calculate(null)).isInstanceOf(NullPointerException.class);
    }

    /** The example scores quoted in the PR and the design doc; change both together. */
    @Test
    void workedExamplesMatchTheDesignDoc() {
        List<CreditAccount> highUtilization = List.of(
                card(12_900, 15_000, 300),
                card(8_600, 10_000, 180),
                loan(AccountType.MORTGAGE, 150_000, 400_000, 144),
                loan(AccountType.AUTO_LOAN, 6_000, 30_000, 48));
        List<CreditAccount> derogatory = new ArrayList<>(withLate(0, 1, 2));
        derogatory.add(new CreditAccount(AccountType.CREDIT_CARD, "Contoso Card", AccountStatus.CHARGED_OFF, 2_000, 2_000, 60, 0, 0, 1));

        assertThat(score(report(pristineAccounts(), 0, 0, 0))).as("clean, seasoned").isEqualTo(846);
        assertThat(score(withLate(1, 0, 0))).as("one 30-day late").isEqualTo(804);
        assertThat(score(report(highUtilization, 0, 0, 0))).as("cards 86% used").isEqualTo(754);
        assertThat(score(report(pristineAccounts(), 0, 1, 0))).as("clean plus one collection").isEqualTo(710);
        assertThat(score(report(List.of(card(100, 1_000, 8)), 1, 0, 0))).as("one card, 8 months").isEqualTo(702);
        assertThat(score(report(derogatory, 2, 1, 1))).as("lates, charge-off, collection, public record").isEqualTo(526);
        assertThat(score(report(List.of(), 0, 0, 0))).as("empty file").isEqualTo(548);
    }

    // ---- untrusted input -----------------------------------------------------------------------

    @Test
    void negativeLateCountsCannotRaiseTheScore() {
        List<CreditAccount> accounts = new ArrayList<>(pristineAccounts());
        accounts.set(0, new CreditAccount(AccountType.CREDIT_CARD, "N", AccountStatus.OPEN, 9_900, 10_000, 300, -50, -1, -1));
        List<CreditAccount> zeroLates = new ArrayList<>(pristineAccounts());
        zeroLates.set(0, card(9_900, 10_000, 300));
        ScoreBreakdown b = calculator.breakdown(report(accounts, 0, 0, 0));
        assertThat(b.paymentHistory()).isEqualTo(1.0);
        assertThat(b.score()).isEqualTo(score(zeroLates));
    }

    @Test
    void negativeReportLevelCountsAreTreatedAsZero() {
        assertThat(score(report(pristineAccounts(), -5, -3, -2))).isEqualTo(score(report(pristineAccounts(), 0, 0, 0)));
    }

    @Test
    void aNegativeBalanceOnOneCardCannotHideAnotherCardsBalance() {
        List<CreditAccount> withCredit = List.of(
                card(-1_000_000, 10_000, 300), card(9_900, 10_000, 300), loan(AccountType.AUTO_LOAN, 6_000, 30_000, 48));
        List<CreditAccount> withZero = List.of(
                card(0, 10_000, 300), card(9_900, 10_000, 300), loan(AccountType.AUTO_LOAN, 6_000, 30_000, 48));
        assertThat(score(withCredit)).isEqualTo(score(withZero));
    }

    @Test
    void negativeLimitsAndAgesAreTreatedAsZero() {
        List<CreditAccount> negative = List.of(card(100, -10_000, -500), card(100, 10_000, 300), card(100, 10_000, 300));
        List<CreditAccount> zero = List.of(card(100, 0, 0), card(100, 10_000, 300), card(100, 10_000, 300));
        assertThat(calculator.breakdown(report(negative, 0, 0, 0))).isEqualTo(calculator.breakdown(report(zero, 0, 0, 0)));
    }

    @Test
    void cardsWithoutALimitAreLeftOutOfUtilization() {
        CreditReport withZeroLimitCard = report(List.of(card(5_000, 0, 120), card(500, 10_000, 120)), 0, 0, 0);
        CreditReport withoutIt = report(List.of(card(500, 10_000, 120)), 0, 0, 0);
        assertThat(FactorBasedScoreCalculator.revolvingUtilizationScore(withZeroLimitCard))
                .isEqualTo(FactorBasedScoreCalculator.revolvingUtilizationScore(withoutIt));
        assertThat(FactorBasedScoreCalculator.revolvingUtilizationScore(report(List.of(card(5_000, 0, 120)), 0, 0, 0)))
                .as("no card has a limit")
                .isEqualTo(0.70);
    }

    @Test
    void hugeAmountsDoNotOverflow() {
        CreditReport cards = report(List.of(card(Long.MAX_VALUE, Long.MAX_VALUE, 120), card(0, Long.MAX_VALUE, 120)), 0, 0, 0);
        // total utilisation 50%, highest card 100%
        assertThat(FactorBasedScoreCalculator.revolvingUtilizationScore(cards))
                .isCloseTo(0.80 * FactorBasedScoreCalculator.utilizationBand(0.5)
                        + 0.20 * FactorBasedScoreCalculator.utilizationBand(1.0), within(1e-9));

        CreditReport loans = report(List.of(
                loan(AccountType.AUTO_LOAN, Long.MAX_VALUE, Long.MAX_VALUE, 40),
                loan(AccountType.AUTO_LOAN, Long.MAX_VALUE, Long.MAX_VALUE, 40)), 0, 0, 0);
        assertThat(FactorBasedScoreCalculator.installmentPaydownScore(loans)).as("nothing paid down").isEqualTo(0.60);
    }

    @Test
    void anAccountWithoutAStatusCountsAsOpen() {
        List<CreditAccount> unknown = new ArrayList<>(pristineAccounts());
        for (int i = 0; i < unknown.size(); i++) {
            CreditAccount a = unknown.get(i);
            unknown.set(i, new CreditAccount(a.type(), a.creditor(), null, a.balance(), a.creditLimit(), a.monthsOpen(), 0, 0, 0));
        }
        assertThat(calculator.breakdown(report(unknown, 0, 0, 0)))
                .isEqualTo(calculator.breakdown(report(pristineAccounts(), 0, 0, 0)));
    }

    @Test
    void anAccountWithoutATypeIsNeitherRevolvingNorInstalment() {
        CreditAccount untyped = new CreditAccount(null, null, AccountStatus.OPEN, 9_000, 10_000, 300, 0, 0, 0);
        CreditReport cardsOnly = report(List.of(card(300, 15_000, 300)), 0, 0, 0);
        CreditReport cardsAndUntyped = report(List.of(card(300, 15_000, 300), untyped), 0, 0, 0);
        assertThat(FactorBasedScoreCalculator.creditMix(cardsAndUntyped)).isEqualTo(FactorBasedScoreCalculator.creditMix(cardsOnly));
        assertThat(FactorBasedScoreCalculator.installmentPaydownScore(cardsAndUntyped)).isEqualTo(0.80);
        assertThat(FactorBasedScoreCalculator.revolvingUtilizationScore(cardsAndUntyped))
                .isEqualTo(FactorBasedScoreCalculator.revolvingUtilizationScore(cardsOnly));
        assertThat(FactorBasedScoreCalculator.creditMix(report(List.of(untyped), 0, 0, 0))).isEqualTo(0.0);
        assertThat(score(report(List.of(untyped), 0, 0, 0))).isBetween(300, 850);
    }

    @Test
    void arbitraryParseableInputAlwaysGivesAScoreInRange() {
        Random random = new Random(42);
        long[] amounts = {Long.MIN_VALUE, -1, 0, 1, 500, 10_000, Long.MAX_VALUE};
        int[] counts = {Integer.MIN_VALUE, -1, 0, 1, 3, 1_000, Integer.MAX_VALUE};
        AccountType[] types = {null, AccountType.CREDIT_CARD, AccountType.MORTGAGE, AccountType.AUTO_LOAN};
        AccountStatus[] statuses = {null, AccountStatus.OPEN, AccountStatus.CLOSED, AccountStatus.CHARGED_OFF};
        for (int i = 0; i < 5_000; i++) {
            List<CreditAccount> accounts = new ArrayList<>();
            for (int n = random.nextInt(6); n > 0; n--) {
                accounts.add(new CreditAccount(
                        types[random.nextInt(types.length)], null, statuses[random.nextInt(statuses.length)],
                        amounts[random.nextInt(amounts.length)], amounts[random.nextInt(amounts.length)],
                        counts[random.nextInt(counts.length)], counts[random.nextInt(counts.length)],
                        counts[random.nextInt(counts.length)], counts[random.nextInt(counts.length)]));
            }
            CreditReport r = new CreditReport(null, null, null, null, random.nextBoolean() ? accounts : null,
                    counts[random.nextInt(counts.length)], counts[random.nextInt(counts.length)],
                    counts[random.nextInt(counts.length)]);
            ScoreBreakdown b = calculator.breakdown(r);
            assertThat(List.of(b.paymentHistory(), b.amountsOwed(), b.historyLength(), b.newCredit(), b.creditMix()))
                    .as("sub-scores for %s", r)
                    .allSatisfy(sub -> assertThat(sub).isBetween(0.0, 1.0));
            assertThat(b.score()).isBetween(300, 850);
        }
    }

    private int score(List<CreditAccount> accounts) {
        return score(report(accounts, 0, 0, 0));
    }

    private int score(CreditReport report) {
        return calculator.calculate(report).value();
    }

    private static List<CreditAccount> withLate(int late30, int late60, int late90) {
        List<CreditAccount> accounts = new ArrayList<>(pristineAccounts());
        CreditAccount first = accounts.getFirst();
        accounts.set(0, new CreditAccount(first.type(), first.creditor(), first.status(), first.balance(),
                first.creditLimit(), first.monthsOpen(), late30, late60, late90));
        return accounts;
    }
}
