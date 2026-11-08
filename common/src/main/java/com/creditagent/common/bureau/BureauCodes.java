package com.creditagent.common.bureau;

import com.creditagent.common.AccountType;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code tables shared by the writer of bureau reports (credit report generator) and their reader
 * (Normalized Report Generator), so both sides agree on every code.
 */
public final class BureauCodes {

    public static final DateTimeFormatter EQUIFAX_DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US);
    public static final DateTimeFormatter EQUIFAX_MONTH = DateTimeFormatter.ofPattern("MM/uuuu", Locale.US);
    public static final DateTimeFormatter EXPERIAN_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    public static final DateTimeFormatter TRANSUNION_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Experian payment-profile months kept, as on a printed Experian report. */
    public static final int EXPERIAN_PROFILE_MONTHS = 84;

    public static final String EXPERIAN_OPEN = "11";
    public static final String EXPERIAN_CLOSED = "13";
    public static final String EXPERIAN_CHARGED_OFF = "97";

    public static final String TRANSUNION_PAID_AS_AGREED = "01";
    public static final String TRANSUNION_CHARGED_OFF = "09";
    public static final String TRANSUNION_CLOSED_REMARK = "CLOSED";

    private static final Pattern LAST_FOUR = Pattern.compile("(\\d{4})(?!.*\\d)");

    private BureauCodes() {}

    /** Metro 2 account type code. */
    public static String metro2AccountType(AccountType type) {
        return switch (type) {
            case AUTO_LOAN -> "00";
            case PERSONAL_LOAN -> "01";
            case STUDENT_LOAN -> "12";
            case CREDIT_CARD -> "18";
            case MORTGAGE -> "26";
        };
    }

    public static AccountType fromMetro2AccountType(String code) {
        return switch (code == null ? "" : code.strip()) {
            case "00" -> AccountType.AUTO_LOAN;
            case "01" -> AccountType.PERSONAL_LOAN;
            case "12" -> AccountType.STUDENT_LOAN;
            case "18" -> AccountType.CREDIT_CARD;
            case "26" -> AccountType.MORTGAGE;
            default -> throw new IllegalArgumentException("Unknown Metro 2 account type code");
        };
    }

    /** Metro 2 portfolio type letter: R revolving, M mortgage, I instalment. */
    public static String portfolioLetter(AccountType type) {
        return switch (type) {
            case CREDIT_CARD -> "R";
            case MORTGAGE -> "M";
            default -> "I";
        };
    }

    public static String transUnionIndustryCode(AccountType type) {
        return switch (type) {
            case CREDIT_CARD -> "BC";
            case AUTO_LOAN -> "FA";
            case MORTGAGE -> "FM";
            case STUDENT_LOAN -> "ES";
            case PERSONAL_LOAN -> "FP";
        };
    }

    public static AccountType fromTransUnionIndustryCode(String code) {
        return switch (code == null ? "" : code.strip().toUpperCase(Locale.ROOT)) {
            case "BC" -> AccountType.CREDIT_CARD;
            case "FA" -> AccountType.AUTO_LOAN;
            case "FM" -> AccountType.MORTGAGE;
            case "ES" -> AccountType.STUDENT_LOAN;
            case "FP" -> AccountType.PERSONAL_LOAN;
            default -> throw new IllegalArgumentException("Unknown TransUnion industry code");
        };
    }

    public static String transUnionPortfolioType(AccountType type) {
        return switch (type) {
            case CREDIT_CARD -> "revolving";
            case MORTGAGE -> "mortgage";
            default -> "installment";
        };
    }

    /** The last four digits in a masked account number, or {@code ""} if there are none. */
    public static String lastFour(String accountNumber) {
        if (accountNumber == null) {
            return "";
        }
        Matcher matcher = LAST_FOUR.matcher(accountNumber);
        return matcher.find() ? matcher.group(1) : "";
    }
}
