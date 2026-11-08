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
