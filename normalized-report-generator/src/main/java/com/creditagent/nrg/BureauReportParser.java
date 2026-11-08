package com.creditagent.nrg;

import static com.creditagent.common.bureau.BureauCodes.EQUIFAX_DATE;
import static com.creditagent.common.bureau.BureauCodes.EQUIFAX_MONTH;
import static com.creditagent.common.bureau.BureauCodes.EXPERIAN_DATE;
import static com.creditagent.common.bureau.BureauCodes.TRANSUNION_DATE;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.common.bureau.BureauCodes;
import com.creditagent.common.bureau.EquifaxReport;
import com.creditagent.common.bureau.ExperianReport;
import com.creditagent.common.bureau.TransUnionReport;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Reads each bureau's own format into one common shape, {@link BureauView}. */
class BureauReportParser {

    BureauView parse(EquifaxReport report) {
        LocalDate reportDate = date(report.dateOfReport(), EQUIFAX_DATE, "Equifax dateOfReport");
        EquifaxReport.Subject subject = required(report.subject(), "Equifax subject");
        List<ReportedAccount> accounts = report.trades().stream()
                .map(t -> {
                    AccountType type = accountType(() -> BureauCodes.fromMetro2AccountType(t.accountType()));
                    AccountStatus status = equifaxStatus(t.rate(), t.closed());
                    long limit = "R".equalsIgnoreCase(t.portfolioType()) ? t.creditLimit() : t.highCredit();
                    YearMonth opened = equifaxMonth(t.dateOpened());
                    return new ReportedAccount(
                            new AccountKey(type, t.memberName(), BureauCodes.lastFour(t.accountNumber())),
                            new CreditAccount(
                                    type, t.memberName(), status, t.balance(), limit,
                                    monthsBetween(opened, reportDate),
                                    t.times30DaysLate(), t.times60DaysLate(), t.times90DaysLate()),
                            date(t.dateReported(), EQUIFAX_DATE, "Equifax dateReported"));
                })
                .toList();
        List<ReportedInquiry> inquiries = report.inquiries().stream()
                .map(i -> new ReportedInquiry(i.memberName(), date(i.inquiryDate(), EQUIFAX_DATE, "Equifax inquiryDate")))
                .toList();
        return new BureauView(
                Bureau.EQUIFAX, reportDate, fullName(subject.firstName(), subject.lastName()),
                lastFour(subject.ssnLast4()), accounts, inquiries, report.collectionItems(), report.publicRecordItems());
    }

    BureauView parse(ExperianReport report) {
        LocalDate reportDate = date(report.reportDate(), EXPERIAN_DATE, "Experian reportDate");
        ExperianReport.Consumer consumer = required(report.consumer(), "Experian consumer");
        List<ReportedAccount> accounts = report.tradelines().stream()
                .map(t -> {
                    AccountType type = accountType(() -> BureauCodes.fromMetro2AccountType(t.accountTypeCode()));
                    AccountStatus status = experianStatus(t.statusCode());
                    String profile = t.paymentProfile() == null ? "" : t.paymentProfile();
                    YearMonth opened = YearMonth.from(date(t.openDate(), EXPERIAN_DATE, "Experian openDate"));
                    return new ReportedAccount(
                            new AccountKey(type, t.subscriberName(), BureauCodes.lastFour(t.accountNumber())),
                            new CreditAccount(
                                    type, titleCase(t.subscriberName()), status,
                                    amount(t.balanceAmount(), "Experian balanceAmount"),
                                    amount(t.amount1(), "Experian amount1"),
                                    monthsBetween(opened, reportDate),
                                    count(profile, '1'), count(profile, '2'), count(profile, '3')),
                            date(t.balanceDate(), EXPERIAN_DATE, "Experian balanceDate"));
                })
                .toList();
        List<ReportedInquiry> inquiries = report.inquiries().stream()
                .map(i -> new ReportedInquiry(i.subscriberName(), date(i.date(), EXPERIAN_DATE, "Experian inquiry date")))
                .toList();
        ExperianReport.Summary summary = report.summary() == null ? new ExperianReport.Summary(0, 0) : report.summary();
        return new BureauView(
                Bureau.EXPERIAN, reportDate, experianName(consumer.name()), lastFour(consumer.ssn()),
                accounts, inquiries, summary.collectionCount(), summary.publicRecordCount());
    }

    BureauView parse(TransUnionReport report) {
        LocalDate reportDate = date(report.reportDate(), TRANSUNION_DATE, "TransUnion reportDate");
        TransUnionReport.Name name = required(report.name(), "TransUnion name");
        List<ReportedAccount> accounts = report.trades().stream()
                .map(t -> {
                    AccountType type = accountType(() -> BureauCodes.fromTransUnionIndustryCode(t.industryCode()));
                    AccountStatus status = transUnionStatus(t.accountRating(), t.remark());
                    long limit = t.creditLimit() != null ? t.creditLimit() : t.highCredit() == null ? 0 : t.highCredit();
                    TransUnionReport.HistoricalCounters lates = t.historicalCounters() == null
                            ? new TransUnionReport.HistoricalCounters(0, 0, 0)
                            : t.historicalCounters();
                    YearMonth opened = YearMonth.from(date(t.dateOpened(), TRANSUNION_DATE, "TransUnion dateOpened"));
                    return new ReportedAccount(
                            new AccountKey(type, t.subscriberName(), BureauCodes.lastFour(t.accountNumber())),
                            new CreditAccount(
                                    type, t.subscriberName(), status, t.currentBalance(), limit,
                                    monthsBetween(opened, reportDate),
                                    lates.late30(), lates.late60(), lates.late90()),
                            date(t.dateEffective(), TRANSUNION_DATE, "TransUnion dateEffective"));
                })
                .toList();
        List<ReportedInquiry> inquiries = report.inquiries().stream()
                .map(i -> new ReportedInquiry(
                        i.subscriberName(), date(i.inquiryDate(), TRANSUNION_DATE, "TransUnion inquiryDate")))
                .toList();
        return new BureauView(
                Bureau.TRANSUNION, reportDate, fullName(name.first(), name.last()), lastFour(report.ssnMasked()),
                accounts, inquiries, report.collectionsCount(), report.publicRecordsCount());
    }

    /**
     * Equifax rate: portfolio letter plus manner-of-payment digit. 1–5 are current to 120+ days late,
     * 7 a wage-earner plan (all still open); 8 repossession and 9 charge-off/collection are derogatory
     * and count as charged off. Anything else is refused rather than read as current.
     */
    private static AccountStatus equifaxStatus(String rate, boolean closed) {
        String code = rate == null ? "" : rate.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[RIMOC][0-9]")) {
            throw new InvalidBureauReportException("Equifax rate is not a known code");
        }
        return switch (code.charAt(1)) {
            case '8', '9' -> AccountStatus.CHARGED_OFF;
            case '1', '2', '3', '4', '5', '7' -> closed ? AccountStatus.CLOSED : AccountStatus.OPEN;
            default -> throw new InvalidBureauReportException("Equifax rate is not a known code");
        };
    }

    /**
     * Experian status (Metro 2 account status): 11 current and 71–84 past due are open; 13 paid or
     * closed; 93 collection, 94 foreclosure, 95 voluntary surrender, 96 repossession and 97 charge-off
     * are derogatory. Anything else is refused.
     */
    private static AccountStatus experianStatus(String statusCode) {
        return switch (statusCode == null ? "" : statusCode.strip()) {
            case BureauCodes.EXPERIAN_OPEN, "71", "78", "80", "82", "83", "84" -> AccountStatus.OPEN;
            case BureauCodes.EXPERIAN_CLOSED -> AccountStatus.CLOSED;
            case "93", "94", "95", "96", BureauCodes.EXPERIAN_CHARGED_OFF -> AccountStatus.CHARGED_OFF;
            default -> throw new InvalidBureauReportException("Experian statusCode is not a known code");
        };
    }

    /**
     * TransUnion rating: 01–05 current to 120+ days late and 07 wage-earner plan are open; 08
     * repossession and 09 charge-off/collection are derogatory. Anything else is refused.
     */
    private static AccountStatus transUnionStatus(String rating, String remark) {
        return switch (rating == null ? "" : rating.strip()) {
            case BureauCodes.TRANSUNION_PAID_AS_AGREED, "02", "03", "04", "05", "07" ->
                    BureauCodes.TRANSUNION_CLOSED_REMARK.equalsIgnoreCase(remark) ? AccountStatus.CLOSED : AccountStatus.OPEN;
            case "08", BureauCodes.TRANSUNION_CHARGED_OFF -> AccountStatus.CHARGED_OFF;
            default -> throw new InvalidBureauReportException("TransUnion accountRating is not a known code");
        };
    }

    private static int monthsBetween(YearMonth opened, LocalDate reportDate) {
        return (int) Math.max(0, ChronoUnit.MONTHS.between(opened, YearMonth.from(reportDate)));
    }

    private static YearMonth equifaxMonth(String value) {
        try {
            return YearMonth.parse(required(value, "Equifax dateOpened").strip(), EQUIFAX_MONTH);
        } catch (DateTimeParseException e) {
            throw new InvalidBureauReportException("Equifax dateOpened is not a valid MM/yyyy month");
        }
    }

    private static int count(String profile, char code) {
        return (int) profile.chars().filter(c -> c == code).count();
    }

    private static String fullName(String first, String last) {
        return ((first == null ? "" : first.strip()) + " " + (last == null ? "" : last.strip())).strip();
    }

    /** {@code AVERY PATEL} style title case from Experian's {@code PATEL, AVERY}. */
    private static String experianName(String name) {
        if (name == null) {
            return "";
        }
        int comma = name.indexOf(',');
        return titleCase(comma < 0 ? name : name.substring(comma + 1).strip() + " " + name.substring(0, comma).strip());
    }

    /** {@code NORTHWIND BANK} to {@code Northwind Bank}: Experian upper-cases names, the others do not. */
    private static String titleCase(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : text.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    private static String lastFour(String masked) {
        String digits = BureauCodes.lastFour(masked);
        if (digits.isEmpty()) {
            throw new InvalidBureauReportException("SSN last four digits are missing");
        }
        return digits;
    }

    private static LocalDate date(String value, DateTimeFormatter format, String field) {
        try {
            return LocalDate.parse(required(value, field).strip(), format);
        } catch (DateTimeParseException e) {
            throw new InvalidBureauReportException(field + " is not a valid date");
        }
    }

    private static long amount(String value, String field) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.strip().replace(",", "").replace("$", ""));
        } catch (NumberFormatException e) {
            throw new InvalidBureauReportException(field + " is not a whole-dollar amount");
        }
    }

    private static AccountType accountType(Supplier<AccountType> lookup) {
        try {
            return lookup.get();
        } catch (IllegalArgumentException e) {
            throw new InvalidBureauReportException(e.getMessage());
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidBureauReportException(field + " is required");
        }
        return value;
    }
}
