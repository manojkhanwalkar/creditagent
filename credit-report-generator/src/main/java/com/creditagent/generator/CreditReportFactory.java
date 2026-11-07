package com.creditagent.generator;

import com.creditagent.common.AccountStatus;
import com.creditagent.common.AccountType;
import com.creditagent.common.CreditAccount;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Produces synthetic but plausible credit reports. No real consumer data is involved. */
public class CreditReportFactory {

    private static final List<String> FIRST_NAMES =
            List.of("Avery", "Jordan", "Riley", "Morgan", "Casey", "Taylor", "Quinn", "Rowan", "Sage", "Emerson");
    private static final List<String> LAST_NAMES =
            List.of("Patel", "Nguyen", "Garcia", "Okafor", "Kowalski", "Haddad", "Larsen", "Moreau", "Silva", "Kim");
    private static final List<String> CARD_ISSUERS =
            List.of("Northwind Bank", "Contoso Card", "Fabrikam Credit", "Tailspin Financial", "Woodgrove Bank");
    private static final List<String> LENDERS =
            List.of("Litware Lending", "Proseware Auto", "Adatum Mortgage", "Wingtip Student Loans", "Lucerne Credit Union");

    private final RandomGenerator random;
    private final Clock clock;

    public CreditReportFactory(RandomGenerator random, Clock clock) {
        this.random = Objects.requireNonNull(random);
        this.clock = Objects.requireNonNull(clock);
    }

    public CreditReport create(Ssn ssn, String fullName) {
        Objects.requireNonNull(ssn, "ssn");
        String name = (fullName == null || fullName.isBlank()) ? randomName() : fullName.strip();

        List<CreditAccount> accounts = new ArrayList<>();
        int cards = random.nextInt(1, 5);
        for (int i = 0; i < cards; i++) {
            accounts.add(creditCard());
        }
        if (random.nextBoolean()) {
            accounts.add(loan(AccountType.AUTO_LOAN, 8_000, 45_000));
        }
        if (random.nextInt(3) == 0) {
            accounts.add(loan(AccountType.MORTGAGE, 120_000, 650_000));
        }
        if (random.nextInt(3) == 0) {
            accounts.add(loan(AccountType.STUDENT_LOAN, 5_000, 90_000));
        }

        return new CreditReport(
                UUID.randomUUID().toString(),
                ssn.masked(),
                name,
                clock.instant(),
                accounts,
                random.nextInt(0, 7),
                random.nextInt(4) == 0 ? random.nextInt(1, 3) : 0,
                random.nextInt(10) == 0 ? 1 : 0);
    }

    private CreditAccount creditCard() {
        long limit = 500L * random.nextInt(2, 41); // 1,000 – 20,000
        long balance = Math.round(limit * random.nextDouble(0.0, 1.05));
        return new CreditAccount(
                AccountType.CREDIT_CARD,
                pick(CARD_ISSUERS),
                random.nextInt(12) == 0 ? AccountStatus.CLOSED : AccountStatus.OPEN,
                balance,
                limit,
                random.nextInt(3, 240),
                lateCount(4),
                lateCount(8),
                lateCount(15));
    }

    private CreditAccount loan(AccountType type, int minAmount, int maxAmount) {
        long original = random.nextInt(minAmount, maxAmount + 1);
        int monthsOpen = random.nextInt(6, 180);
        long balance = Math.max(0, Math.round(original * (1.0 - monthsOpen / 240.0)));
        return new CreditAccount(
                type,
                pick(LENDERS),
                AccountStatus.OPEN,
                balance,
                original,
                monthsOpen,
                lateCount(5),
                lateCount(10),
                lateCount(20));
    }

    /** Returns 0 most of the time; occasionally 1–3 late payments. */
    private int lateCount(int oneIn) {
        return random.nextInt(oneIn) == 0 ? random.nextInt(1, 4) : 0;
    }

    private String randomName() {
        return pick(FIRST_NAMES) + " " + pick(LAST_NAMES);
    }

    private String pick(List<String> values) {
        return values.get(random.nextInt(values.size()));
    }
}
