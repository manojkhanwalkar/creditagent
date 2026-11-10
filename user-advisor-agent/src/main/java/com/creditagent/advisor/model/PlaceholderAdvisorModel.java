package com.creditagent.advisor.model;

import com.creditagent.advisor.guard.TextNormalizer;
import com.creditagent.advisor.memory.ConversationMemory.Turn;
import com.creditagent.common.AccountStatus;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stand-in for an LLM: answers from the report by matching the question to a topic. It uses the
 * conversation memory the way a model would: a follow-up such as "tell me more" or "why?" continues
 * the topic of the previous answer. Deterministic, so the guardrails and memory can be tested.
 */
public class PlaceholderAdvisorModel implements AdvisorModel {

    /** What a question is about, in the order topics are tried. */
    enum Topic {
        GREETING("hi", "hello", "hey", "good morning", "good afternoon", "good evening"),
        THANKS("thank", "thanks"),
        // Specific topics before broad ones: "late payments on my card" is about payments, and
        // "should I pay off my collection account" about collections.
        PAYMENTS("late", "miss", "payment", "overdue", "delinquen", "on time"),
        INQUIRIES("inquir", "enquir", "applied", "apply", "applica", "hard pull"),
        NEGATIVES("collection", "charge", "public record", "bankrupt", "lien", "negative"),
        UTILIZATION("utiliz", "utilis", "balance", "limit", "card", "revolv", "owe", "debt"),
        IMPROVE("improv", "raise", "increas", "boost", "better", "build", "rebuild", "tip", "advice", "should i"),
        ACCOUNTS("account", "oldest", "newest", "age", "how long", "loan", "mortgage", "open", "closed", "mix"),
        SUMMARY("score", "report", "summary", "overview", "explain", "why", "how am i doing");

        final List<String> cues;

        Topic(String... cues) {
            this.cues = List.of(cues);
        }

        boolean matches(String folded) {
            return cues.stream().anyMatch(cue -> cue.contains(" ") ? folded.contains(cue)
                    : Arrays.stream(folded.split(" ")).anyMatch(token -> token.startsWith(cue)));
        }
    }

    static final List<String> FOLLOW_UP_CUES = List.of("more", "why", "how", "that", "this", "it", "explain", "elaborate", "detail");

    @Override
    public String answer(String question, List<Turn> history, CreditReport report) {
        String folded = TextNormalizer.fold(question);
        Topic topic = topicOf(folded);
        Topic previous = history.isEmpty() ? null : previousTopic(history.getLast().answer());
        boolean followUp = previous != null && (topic == null || topic == Topic.SUMMARY)
                && FOLLOW_UP_CUES.stream().anyMatch(cue -> Arrays.asList(folded.split(" ")).contains(cue));
        if (followUp) {
            return detail(previous, report);
        }
        return reply(topic == null ? Topic.SUMMARY : topic, report);
    }

    static Topic topicOf(String folded) {
        return Arrays.stream(Topic.values()).filter(t -> t.matches(folded)).findFirst().orElse(null);
    }

    /** The topic an earlier answer was about, from its opening words. */
    static Topic previousTopic(String answer) {
        for (Topic topic : Topic.values()) {
            if (answer.startsWith(opening(topic))) {
                return topic;
            }
        }
        return null;
    }

    static String opening(Topic topic) {
        return switch (topic) {
            case GREETING -> "Hello!";
            case THANKS -> "You're welcome.";
            case IMPROVE -> "To raise your score,";
            case UTILIZATION -> "Your card balances";
            case PAYMENTS -> "Your payment history";
            case INQUIRIES -> "Hard inquiries:";
            case NEGATIVES -> "Negative items:";
            case ACCOUNTS -> "Your accounts:";
            case SUMMARY -> "Here is your report at a glance.";
        };
    }

    private String reply(Topic topic, CreditReport report) {
        String start = opening(topic) + " ";
        return switch (topic) {
            case GREETING -> start + "Ask me about your payment history, card balances, hard inquiries, accounts, or how to"
                    + " improve your score.";
            case THANKS -> start + "Anything else about your report?";
            case IMPROVE -> start + sentences(improvements(report));
            case UTILIZATION -> start + String.format(Locale.ROOT, "add up to $%,d of $%,d in limits, %d%% utilisation. %s",
                    report.totalRevolvingBalance(), report.totalRevolvingLimit(), percent(report.revolvingUtilization()),
                    utilisationAdvice(report.revolvingUtilization()));
            case PAYMENTS -> start + (report.totalLatePayments() == 0
                    ? "shows no late payments. Paying every bill on time is the biggest single factor in a score."
                    : String.format(Locale.ROOT, "shows %d late payments (%s). Payment history is the biggest single factor;"
                            + " late payments weigh less as they age, so every on-time month helps.",
                            report.totalLatePayments(), lateBreakdown(report)));
            case INQUIRIES -> start + String.format(Locale.ROOT, "%d in the last 12 months. %s", report.hardInquiriesLast12Months(),
                    report.hardInquiriesLast12Months() > 2
                            ? "Several applications close together can lower a score; space out new applications."
                            : "That is a modest number. Inquiries affect a score for about a year.");
            case NEGATIVES -> start + String.format(Locale.ROOT, "%d collections, %d public records and %d charged-off accounts. %s",
                    report.collections(), report.publicRecords(), chargedOff(report),
                    report.collections() + report.publicRecords() + chargedOff(report) == 0
                            ? "None is a good sign."
                            : "These weigh heavily. If one is wrong, you can dispute it with the bureau that reports it.");
            case ACCOUNTS -> start + String.format(Locale.ROOT, "%d in total, %d open, %d kinds. The oldest has been open %s.",
                    report.accounts().size(), openAccounts(report), report.distinctAccountTypes(),
                    months(report.oldestAccountMonths()));
            case SUMMARY -> start + String.format(Locale.ROOT, "%d accounts (%d open), %d%% card utilisation, %d late payments,"
                    + " %d hard inquiries in the last year, %d collections. Ask me about any of these.",
                    report.accounts().size(), openAccounts(report), percent(report.revolvingUtilization()),
                    report.totalLatePayments(), report.hardInquiriesLast12Months(), report.collections());
        };
    }

    private String detail(Topic topic, CreditReport report) {
        String start = opening(topic) + " ";
        return switch (topic) {
            case UTILIZATION -> {
                List<String> cards = report.accounts().stream()
                        .filter(a -> a.isOpen() && a.creditLimit() > 0 && a.type().name().equals("CREDIT_CARD"))
                        .sorted(Comparator.comparingDouble((CreditAccount a) -> (double) a.balance() / a.creditLimit()).reversed())
                        .map(a -> String.format(Locale.ROOT, "%s %d%%", a.creditor(), percent((double) a.balance() / a.creditLimit())))
                        .toList();
                yield cards.isEmpty()
                        ? start + "by card: you have no open cards with a credit limit, so there is no card utilisation."
                        : start + "by card: " + String.join("; ", cards) + ". Paying down the highest first helps most.";
            }
            case PAYMENTS -> start + "by account: " + String.join("; ", report.accounts().stream()
                    .filter(a -> a.totalLatePayments() > 0)
                    .map(a -> String.format(Locale.ROOT, "%s %d late", a.creditor(), a.totalLatePayments()))
                    .toList()) + (report.totalLatePayments() == 0 ? "none late." : ". Set up automatic payments to avoid more.");
            case ACCOUNTS -> start + "by age: " + String.join("; ", report.accounts().stream()
                    .sorted(Comparator.comparingInt(CreditAccount::monthsOpen).reversed())
                    .map(a -> a.creditor() + " " + months(a.monthsOpen()))
                    .toList()) + ". Keeping old accounts open lengthens your history.";
            default -> reply(topic, report) + " Ask about a specific part of your report for more detail.";
        };
    }

    private static List<String> improvements(CreditReport report) {
        List<String> tips = new ArrayList<>();
        if (report.totalLatePayments() > 0) {
            tips.add("pay every bill on time from now on: payment history counts most.");
        }
        if (report.revolvingUtilization() > 0.3) {
            tips.add(String.format(Locale.ROOT, "bring card balances below 30%% of your limits (you are at %d%%).",
                    percent(report.revolvingUtilization())));
        }
        if (report.hardInquiriesLast12Months() > 2) {
            tips.add("avoid new credit applications for a while.");
        }
        if (report.collections() > 0) {
            tips.add("deal with the accounts in collections.");
        }
        if (tips.isEmpty()) {
            tips.add("keep doing what you are doing: on-time payments, low balances and old accounts kept open.");
        }
        return tips;
    }

    /** The first tip follows "To raise your score,"; the rest start new sentences. */
    static String sentences(List<String> tips) {
        StringBuilder text = new StringBuilder(tips.getFirst());
        for (String tip : tips.subList(1, tips.size())) {
            text.append(' ').append(Character.toUpperCase(tip.charAt(0))).append(tip.substring(1));
        }
        return text.toString();
    }

    private static String utilisationAdvice(double utilization) {
        if (utilization > 0.3) {
            return "Below 30% is better, and below 10% is best.";
        }
        return utilization > 0.1 ? "That is good; below 10% is best." : "That is excellent.";
    }

    private static String lateBreakdown(CreditReport report) {
        int late30 = report.accounts().stream().mapToInt(CreditAccount::latePayments30).sum();
        int late60 = report.accounts().stream().mapToInt(CreditAccount::latePayments60).sum();
        int late90 = report.accounts().stream().mapToInt(CreditAccount::latePayments90).sum();
        return String.format(Locale.ROOT, "%d at 30 days, %d at 60, %d at 90 or more", late30, late60, late90);
    }

    private static long openAccounts(CreditReport report) {
        return report.accounts().stream().filter(CreditAccount::isOpen).count();
    }

    private static long chargedOff(CreditReport report) {
        return report.accounts().stream().filter(a -> a.status() == AccountStatus.CHARGED_OFF).count();
    }

    private static int percent(double fraction) {
        return (int) Math.round(fraction * 100);
    }

    private static String months(int months) {
        return months >= 24 ? (months / 12) + " years" : months + " months";
    }
}
