package com.creditagent.nrg;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.common.bureau.BureauReports;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Merges one consumer's Equifax, Experian and TransUnion reports into a single {@link CreditReport}.
 *
 * <p>Merge rules:
 * <ul>
 *   <li><b>Identity.</b> All reports must carry the same SSN last four, or the request is refused.
 *       The name comes from Equifax, then TransUnion, then Experian (which upper-cases it).
 *   <li><b>Accounts.</b> The same tradeline is matched across bureaus by type, creditor and account
 *       number last four ({@link AccountKey}). The balance comes from the bureau with the newest
 *       update. Credit limit, age and each late-payment counter take the highest value reported.
 *       Status is charged off if any bureau says so, otherwise the newest bureau's status.
 *   <li><b>Inquiries.</b> A hard inquiry is a pull from one bureau, so the inquiries from all
 *       bureaus are combined, duplicates (same subscriber, same day) removed, and those in the 12
 *       months before the newest report counted.
 *   <li><b>Collections and public records.</b> The highest count any bureau reports.
 * </ul>
 */
class ReportNormalizer {

    /** Order used when bureaus disagree on display text (names). */
    private static final List<Bureau> DISPLAY_PREFERENCE = List.of(Bureau.EQUIFAX, Bureau.TRANSUNION, Bureau.EXPERIAN);

    private final BureauReportParser parser;
    private final Clock clock;

    ReportNormalizer(BureauReportParser parser, Clock clock) {
        this.parser = Objects.requireNonNull(parser);
        this.clock = Objects.requireNonNull(clock);
    }

    CreditReport normalize(BureauReports reports) {
        if (reports == null || reports.isEmpty()) {
            throw new InvalidBureauReportException("At least one bureau report is required");
        }
        List<BureauView> views = new ArrayList<>();
        if (reports.equifax() != null) {
            views.add(parser.parse(reports.equifax()));
        }
        if (reports.experian() != null) {
            views.add(parser.parse(reports.experian()));
        }
        if (reports.transunion() != null) {
            views.add(parser.parse(reports.transunion()));
        }
        views.sort(Comparator.comparingInt(v -> DISPLAY_PREFERENCE.indexOf(v.bureau())));

        String lastFour = views.getFirst().ssnLastFour();
        if (views.stream().anyMatch(v -> !v.ssnLastFour().equals(lastFour))) {
            throw new InvalidBureauReportException("Bureau reports are for different consumers");
        }

        return new CreditReport(
                UUID.randomUUID().toString(),
                "***-**-" + lastFour,
                views.stream().map(BureauView::fullName).filter(n -> !n.isBlank()).findFirst().orElse(""),
                clock.instant(),
                mergeAccounts(views),
                countRecentInquiries(views),
                views.stream().mapToInt(BureauView::collections).max().orElse(0),
                views.stream().mapToInt(BureauView::publicRecords).max().orElse(0));
    }

    private static List<CreditAccount> mergeAccounts(List<BureauView> views) {
        // LinkedHashMap keeps a stable order: accounts as the preferred bureau lists them.
        Map<AccountKey, List<ReportedAccount>> byKey = new LinkedHashMap<>();
        for (BureauView view : views) {
            for (ReportedAccount account : view.accounts()) {
                byKey.computeIfAbsent(account.key(), k -> new ArrayList<>()).add(account);
            }
        }
        return byKey.values().stream().map(ReportNormalizer::merge).toList();
    }

    /** {@code reports} is in display-preference order, so the first entry names the creditor. */
    private static CreditAccount merge(List<ReportedAccount> reports) {
        ReportedAccount newest = reports.stream()
                .max(Comparator.comparing(ReportedAccount::reportedOn))
                .orElseThrow();
        boolean chargedOff = reports.stream().anyMatch(r -> r.account().status() == AccountStatus.CHARGED_OFF);
        CreditAccount latest = newest.account();
        return new CreditAccount(
                latest.type(),
                reports.getFirst().account().creditor(),
                chargedOff ? AccountStatus.CHARGED_OFF : latest.status(),
                latest.balance(),
                max(reports, CreditAccount::creditLimit),
                (int) max(reports, CreditAccount::monthsOpen),
                (int) max(reports, CreditAccount::latePayments30),
                (int) max(reports, CreditAccount::latePayments60),
                (int) max(reports, CreditAccount::latePayments90));
    }

    private static long max(List<ReportedAccount> reports, ToLongFunction<CreditAccount> field) {
        return reports.stream().mapToLong(r -> field.applyAsLong(r.account())).max().orElse(0);
    }

    private static int countRecentInquiries(List<BureauView> views) {
        LocalDate asOf = views.stream().map(BureauView::reportDate).max(Comparator.naturalOrder()).orElseThrow();
        LocalDate cutoff = asOf.minusMonths(12);
        Set<ReportedInquiry> distinct = new HashSet<>();
        for (BureauView view : views) {
            for (ReportedInquiry inquiry : view.inquiries()) {
                if (inquiry.date().isAfter(cutoff) && !inquiry.date().isAfter(asOf)) {
                    distinct.add(inquiry);
                }
            }
        }
        return distinct.size();
    }
}
