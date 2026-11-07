package com.creditagent.agent.advice;

import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Recommendation;
import com.creditagent.common.Recommendation.Impact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic advice based on the factors that drive most scoring models: payment history,
 * utilisation, derogatory marks, recent inquiries, length of history and credit mix.
 */
public class RuleBasedCreditAdvisor implements CreditAdvisor {

    static final double HIGH_UTILIZATION = 0.30;
    static final double IDEAL_UTILIZATION = 0.10;
    static final int MANY_INQUIRIES = 3;
    static final int SHORT_HISTORY_MONTHS = 36;

    @Override
    public CreditAdvice advise(CreditReport report, CreditScore score) {
        List<Draft> drafts = new ArrayList<>();

        int late = report.totalLatePayments();
        if (late > 0) {
            drafts.add(new Draft(
                    Impact.HIGH,
                    10,
                    "Make every payment on time",
                    "Your report shows " + late + " late payment(s). Payment history is the largest scoring factor. "
                            + "Set up autopay for at least the minimum on every account."));
        }

        if (report.collections() > 0 || report.publicRecords() > 0) {
            drafts.add(new Draft(
                    Impact.HIGH,
                    9,
                    "Resolve collections and public records",
                    "There are " + report.collections() + " collection account(s) and " + report.publicRecords()
                            + " public record(s). Contact the collector to settle or set up a plan, ask for a "
                            + "pay-for-delete in writing, and dispute anything inaccurate with the bureaus."));
        }

        double utilization = report.revolvingUtilization();
        if (utilization > HIGH_UTILIZATION) {
            long target = Math.round(report.totalRevolvingLimit() * HIGH_UTILIZATION);
            long payDown = Math.max(0, report.totalRevolvingBalance() - target);
            drafts.add(new Draft(
                    Impact.HIGH,
                    8,
                    "Lower your credit card utilization",
                    "You are using " + percent(utilization) + " of your available revolving credit. Paying down about $"
                            + payDown + " would bring you under 30%; under 10% is better still."));
        } else if (utilization > IDEAL_UTILIZATION) {
            drafts.add(new Draft(
                    Impact.MEDIUM,
                    5,
                    "Keep utilization under 10%",
                    "Utilization is " + percent(utilization) + ". Paying balances before the statement date keeps the "
                            + "reported balance low."));
        }

        List<CreditAccount> maxedCards = report.accounts().stream()
                .filter(a -> a.type() == AccountType.CREDIT_CARD && a.isOpen() && a.creditLimit() > 0)
                .filter(a -> (double) a.balance() / a.creditLimit() >= 0.9)
                .toList();
        if (!maxedCards.isEmpty()) {
            drafts.add(new Draft(
                    Impact.MEDIUM,
                    6,
                    "Pay down cards that are near their limit",
                    maxedCards.size() + " card(s) are at 90% or more of their limit (for example "
                            + maxedCards.getFirst().creditor() + "). Per-card utilization matters as well as the total."));
        }

        if (report.hardInquiriesLast12Months() >= MANY_INQUIRIES) {
            drafts.add(new Draft(
                    Impact.LOW,
                    3,
                    "Pause new credit applications",
                    report.hardInquiriesLast12Months() + " hard inquiries in the last 12 months. Each one can cost a "
                            + "few points; wait 6–12 months before applying again."));
        }

        if (report.oldestAccountMonths() < SHORT_HISTORY_MONTHS) {
            drafts.add(new Draft(
                    Impact.LOW,
                    2,
                    "Keep your oldest accounts open",
                    "Your oldest account is " + report.oldestAccountMonths() + " months old. Avoid closing old cards; "
                            + "a longer average age helps over time."));
        }

        if (report.distinctAccountTypes() < 2) {
            drafts.add(new Draft(
                    Impact.LOW,
                    1,
                    "Build a mix of credit types",
                    "All your accounts are the same type. A mix of revolving and instalment credit helps slightly, but "
                            + "only take on a loan you actually need."));
        }

        if (drafts.isEmpty()) {
            drafts.add(new Draft(
                    Impact.LOW,
                    0,
                    "Keep doing what you are doing",
                    "No negative factors stand out. Keep paying on time and keep balances low."));
        }

        List<Draft> sorted = drafts.stream()
                .sorted(Comparator.comparingInt(Draft::weight).reversed())
                .toList();
        List<Recommendation> recommendations = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Draft d = sorted.get(i);
            recommendations.add(new Recommendation(i + 1, d.title(), d.detail(), d.impact()));
        }

        return new CreditAdvice(
                report.ssnMasked(), score.value(), score.band(), summary(score, recommendations), recommendations, "rules");
    }

    private static String summary(CreditScore score, List<Recommendation> recommendations) {
        return "Score " + score.value() + " (" + score.band() + "). Top priority: "
                + recommendations.getFirst().title().toLowerCase() + ".";
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    private record Draft(Impact impact, int weight, String title, String detail) {}
}
