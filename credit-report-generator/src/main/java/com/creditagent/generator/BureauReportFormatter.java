package com.creditagent.generator;

import static com.creditagent.common.bureau.BureauCodes.EQUIFAX_DATE;
import static com.creditagent.common.bureau.BureauCodes.EQUIFAX_MONTH;
import static com.creditagent.common.bureau.BureauCodes.EXPERIAN_DATE;
import static com.creditagent.common.bureau.BureauCodes.TRANSUNION_DATE;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.common.bureau.BureauCodes;
import com.creditagent.common.bureau.BureauReports;
import com.creditagent.common.bureau.EquifaxReport;
import com.creditagent.common.bureau.ExperianReport;
import com.creditagent.common.bureau.TransUnionReport;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Turns one consumer's "true" credit history into the three reports the bureaus would each hold,
 * in each bureau's own format.
 *
 * <p>The views differ the way real bureau files do:
 * <ul>
 *   <li>Not every lender reports to every bureau: each account goes to each bureau with
 *       probability {@value #REPORTING_PROBABILITY}, and always to at least one.
 *   <li>Bureaus receive updates on different days, so a bureau whose update is older shows an
 *       older balance. The bureau(s) with the newest update show the true balance.
 *   <li>A hard inquiry is a pull from one bureau, so each inquiry appears at exactly one bureau.
 * </ul>
 * Late-payment counters, collections and public records are the same at every bureau that has the
 * account. Merging the three views therefore gives back the true history.
 */
public class BureauReportFormatter {

    static final double REPORTING_PROBABILITY = 0.85;
    static final int MAX_UPDATE_LAG_DAYS = 30;

    private static final List<String> INQUIRY_SUBSCRIBERS = List.of(
            "Northwind Bank", "Contoso Card", "Fabrikam Credit", "Litware Lending", "Proseware Auto",
            "Adatum Mortgage", "Lucerne Credit Union");

    private final RandomGenerator random;

    public BureauReportFormatter(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    /** One account as seen by the bureaus that carry it. */
    private record Tradeline(CreditAccount account, String lastFour, Map<Bureau, Integer> lagDays) {

        boolean reportedTo(Bureau bureau) {
            return lagDays.containsKey(bureau);
        }

        boolean isNewest(Bureau bureau) {
            int lag = lagDays.get(bureau);
            return lagDays.values().stream().allMatch(other -> lag <= other);
        }
    }

    private record Inquiry(String subscriber, LocalDate date, Bureau bureau) {}

    public BureauReports format(CreditReport report) {
        LocalDate reportDate = LocalDate.ofInstant(report.generatedAt(), ZoneOffset.UTC);
        List<Tradeline> tradelines = report.accounts().stream().map(this::tradeline).toList();
        List<Inquiry> inquiries = inquiries(report.hardInquiriesLast12Months(), reportDate);
        String[] name = splitName(report.fullName());
        String lastFour = report.ssnMasked().substring(report.ssnMasked().length() - 4);

        return new BureauReports(
                equifax(report, reportDate, tradelines, inquiries, name, lastFour),
                experian(report, reportDate, tradelines, inquiries, name, lastFour),
                transUnion(report, reportDate, tradelines, inquiries, name, lastFour));
    }

    private Tradeline tradeline(CreditAccount account) {
        Map<Bureau, Integer> lag = new EnumMap<>(Bureau.class);
        for (Bureau bureau : Bureau.values()) {
            if (random.nextDouble() < REPORTING_PROBABILITY) {
                lag.put(bureau, random.nextInt(MAX_UPDATE_LAG_DAYS + 1));
            }
        }
        if (lag.isEmpty()) {
            lag.put(Bureau.values()[random.nextInt(Bureau.values().length)], random.nextInt(MAX_UPDATE_LAG_DAYS + 1));
        }
        return new Tradeline(account, "%04d".formatted(random.nextInt(10_000)), lag);
    }

    private List<Inquiry> inquiries(int count, LocalDate reportDate) {
        Set<String> seen = new HashSet<>();
        List<Inquiry> inquiries = new ArrayList<>();
        while (inquiries.size() < count) {
            String subscriber = INQUIRY_SUBSCRIBERS.get(random.nextInt(INQUIRY_SUBSCRIBERS.size()));
            LocalDate date = reportDate.minusDays(random.nextInt(1, 360));
            // Two pulls by the same lender on the same day would be merged as one; keep them distinct.
            if (seen.add(subscriber + date)) {
                inquiries.add(new Inquiry(subscriber, date, Bureau.values()[random.nextInt(Bureau.values().length)]));
            }
        }
        return inquiries;
    }

    /** The balance this bureau shows: the true one if its update is the newest, else an older one. */
    private long reportedBalance(Tradeline tradeline, Bureau bureau) {
        CreditAccount account = tradeline.account();
        if (tradeline.isNewest(bureau) || account.balance() == 0) {
            return account.balance();
        }
        if (account.type() == AccountType.CREDIT_CARD) {
            return Math.round(account.balance() * random.nextDouble(0.85, 1.0));
        }
        // An older instalment balance is a little higher: one fewer payment made.
        return Math.min(account.creditLimit(), account.balance() + Math.max(1, account.creditLimit() / 240));
    }

    private EquifaxReport equifax(
            CreditReport report, LocalDate reportDate, List<Tradeline> tradelines, List<Inquiry> inquiries,
            String[] name, String lastFour) {
        List<EquifaxReport.Trade> trades = new ArrayList<>();
        for (Tradeline t : tradelines) {
            if (!t.reportedTo(Bureau.EQUIFAX)) {
                continue;
            }
            CreditAccount a = t.account();
            boolean revolving = a.type() == AccountType.CREDIT_CARD;
            long balance = reportedBalance(t, Bureau.EQUIFAX);
            String portfolio = BureauCodes.portfolioLetter(a.type());
            trades.add(new EquifaxReport.Trade(
                    a.creditor(),
                    "************" + t.lastFour(),
                    portfolio,
                    BureauCodes.metro2AccountType(a.type()),
                    reportDate.minusMonths(a.monthsOpen()).format(EQUIFAX_MONTH),
                    reportDate.minusDays(t.lagDays().get(Bureau.EQUIFAX)).format(EQUIFAX_DATE),
                    revolving ? Math.max(balance, a.balance()) : a.creditLimit(),
                    revolving ? a.creditLimit() : 0,
                    balance,
                    portfolio + (a.status() == AccountStatus.CHARGED_OFF ? "9" : "1"),
                    a.latePayments30(),
                    a.latePayments60(),
                    a.latePayments90(),
                    a.status() == AccountStatus.CLOSED));
        }
        return new EquifaxReport(
                "EFX-" + UUID.randomUUID(),
                reportDate.format(EQUIFAX_DATE),
                new EquifaxReport.Subject(name[0], name[1], lastFour),
                trades,
                inquiries.stream()
                        .filter(i -> i.bureau() == Bureau.EQUIFAX)
                        .map(i -> new EquifaxReport.Inquiry(i.subscriber(), i.date().format(EQUIFAX_DATE)))
                        .toList(),
                report.collections(),
                report.publicRecords());
    }

    private ExperianReport experian(
            CreditReport report, LocalDate reportDate, List<Tradeline> tradelines, List<Inquiry> inquiries,
            String[] name, String lastFour) {
        List<ExperianReport.Tradeline> lines = new ArrayList<>();
        for (Tradeline t : tradelines) {
            if (!t.reportedTo(Bureau.EXPERIAN)) {
                continue;
            }
            CreditAccount a = t.account();
            lines.add(new ExperianReport.Tradeline(
                    a.creditor().toUpperCase(Locale.ROOT),
                    "XXXXXXXX" + t.lastFour(),
                    BureauCodes.metro2AccountType(a.type()),
                    reportDate.minusMonths(a.monthsOpen()).format(EXPERIAN_DATE),
                    reportDate.minusDays(t.lagDays().get(Bureau.EXPERIAN)).format(EXPERIAN_DATE),
                    Long.toString(reportedBalance(t, Bureau.EXPERIAN)),
                    Long.toString(a.creditLimit()),
                    switch (a.status()) {
                        case OPEN -> BureauCodes.EXPERIAN_OPEN;
                        case CLOSED -> BureauCodes.EXPERIAN_CLOSED;
                        case CHARGED_OFF -> BureauCodes.EXPERIAN_CHARGED_OFF;
                    },
                    paymentProfile(a)));
        }
        return new ExperianReport(
                "EXP-" + UUID.randomUUID(),
                reportDate.format(EXPERIAN_DATE),
                new ExperianReport.Consumer(
                        (name[1] + ", " + name[0]).toUpperCase(Locale.ROOT), "XXXXX" + lastFour),
                lines,
                inquiries.stream()
                        .filter(i -> i.bureau() == Bureau.EXPERIAN)
                        .map(i -> new ExperianReport.Inquiry(
                                i.subscriber().toUpperCase(Locale.ROOT), i.date().format(EXPERIAN_DATE)))
                        .toList(),
                new ExperianReport.Summary(report.collections(), report.publicRecords()));
    }

    /**
     * Experian's payment grid, newest month first: {@code 0} current, {@code 1}/{@code 2}/{@code 3}
     * for 30/60/90 days late. Every late payment gets its own month, so the counts survive.
     */
    private String paymentProfile(CreditAccount account) {
        int lates = account.totalLatePayments();
        int months = Math.min(BureauCodes.EXPERIAN_PROFILE_MONTHS, Math.max(account.monthsOpen(), lates));
        char[] grid = "0".repeat(months).toCharArray();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            free.add(i);
        }
        place(grid, free, '1', account.latePayments30());
        place(grid, free, '2', account.latePayments60());
        place(grid, free, '3', account.latePayments90());
        return new String(grid);
    }

    private void place(char[] grid, List<Integer> free, char code, int count) {
        for (int i = 0; i < count && !free.isEmpty(); i++) {
            grid[free.remove(random.nextInt(free.size()))] = code;
        }
    }

    private TransUnionReport transUnion(
            CreditReport report, LocalDate reportDate, List<Tradeline> tradelines, List<Inquiry> inquiries,
            String[] name, String lastFour) {
        List<TransUnionReport.Trade> trades = new ArrayList<>();
        for (Tradeline t : tradelines) {
            if (!t.reportedTo(Bureau.TRANSUNION)) {
                continue;
            }
            CreditAccount a = t.account();
            boolean revolving = a.type() == AccountType.CREDIT_CARD;
            long balance = reportedBalance(t, Bureau.TRANSUNION);
            trades.add(new TransUnionReport.Trade(
                    a.creditor(),
                    t.lastFour() + "****",
                    BureauCodes.transUnionIndustryCode(a.type()),
                    BureauCodes.transUnionPortfolioType(a.type()),
                    reportDate.minusMonths(a.monthsOpen()).format(TRANSUNION_DATE),
                    reportDate.minusDays(t.lagDays().get(Bureau.TRANSUNION)).format(TRANSUNION_DATE),
                    balance,
                    revolving ? a.creditLimit() : null,
                    revolving ? Math.max(balance, a.balance()) : a.creditLimit(),
                    a.status() == AccountStatus.CHARGED_OFF
                            ? BureauCodes.TRANSUNION_CHARGED_OFF
                            : BureauCodes.TRANSUNION_PAID_AS_AGREED,
                    new TransUnionReport.HistoricalCounters(a.latePayments30(), a.latePayments60(), a.latePayments90()),
                    a.status() == AccountStatus.CLOSED ? BureauCodes.TRANSUNION_CLOSED_REMARK : null));
        }
        return new TransUnionReport(
                "TU-" + UUID.randomUUID(),
                reportDate.format(TRANSUNION_DATE),
                new TransUnionReport.Name(name[0], name[1]),
                "***-**-" + lastFour,
                trades,
                inquiries.stream()
                        .filter(i -> i.bureau() == Bureau.TRANSUNION)
                        .map(i -> new TransUnionReport.Inquiry(i.subscriber(), i.date().format(TRANSUNION_DATE)))
                        .toList(),
                report.collections(),
                report.publicRecords());
    }

    /** {@code [first, last]}; everything before the last space is the first name. */
    static String[] splitName(String fullName) {
        String name = fullName == null ? "" : fullName.strip();
        int space = name.lastIndexOf(' ');
        return space < 0 ? new String[] {name, ""} : new String[] {name.substring(0, space), name.substring(space + 1)};
    }
}
