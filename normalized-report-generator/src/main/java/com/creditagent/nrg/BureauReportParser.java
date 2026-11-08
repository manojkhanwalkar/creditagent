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
                    AccountStatus status = t.rate() != null && t.rate().strip().endsWith("9")
                            ? AccountStatus.CHARGED_OFF
                            : t.closed() ? AccountStatus.CLOSED : AccountStatus.OPEN;
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
                    AccountStatus status = switch (t.statusCode() == null ? "" : t.statusCode().strip()) {
                        case BureauCodes.EXPERIAN_CHARGED_OFF -> AccountStatus.CHARGED_OFF;
                        case BureauCodes.EXPERIAN_CLOSED -> AccountStatus.CLOSED;
                        default -> AccountStatus.OPEN;
                    };
                    String profile = t.paymentProfile() == null ? "" : t.paymentProfile();
                    YearMonth opened = YearMonth.from(date(t.openDate(), EXPERIAN_DATE, "Experian openDate"));
                    return new ReportedAccount(
                            new AccountKey(type, t.subscriberName(), BureauCodes.lastFour(t.accountNumber())),
                            new CreditAccount(
                                    type, t.subscriberName(), status,
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
                    AccountStatus status = BureauCodes.TRANSUNION_CHARGED_OFF.equals(t.accountRating())
                            ? AccountStatus.CHARGED_OFF
                            : BureauCodes.TRANSUNION_CLOSED_REMARK.equalsIgnoreCase(t.remark())
                                    ? AccountStatus.CLOSED
                                    : AccountStatus.OPEN;
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
        String reordered = comma < 0 ? name : name.substring(comma + 1).strip() + " " + name.substring(0, comma).strip();
        StringBuilder out = new StringBuilder();
        for (String word : reordered.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
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
