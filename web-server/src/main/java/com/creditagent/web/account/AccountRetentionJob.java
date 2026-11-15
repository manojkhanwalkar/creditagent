package com.creditagent.web.account;

import com.creditagent.common.SafeLogging;
import com.creditagent.web.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Once a day: deletes accounts not used (no login) for {@code credit.account.retention} (GLBA: dispose
 * of customer information no later than two years after last use), and removes the phone numbers
 * stored before they stopped being collected.
 *
 * <p>Looks at up to {@code max-per-run} accounts a run and keeps its place in memory, so a large table
 * is covered over several days; a restart starts over, which only repeats checks. One account's
 * failure is logged (class only) and the run carries on.
 */
public class AccountRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AccountRetentionJob.class);

    /** What one run did. */
    public record Run(int checked, int purged, int phonesRemoved, int failed) {}

    private final UserRepository users;
    private final AccountService accounts;
    private final AccountProperties properties;
    private final Clock clock;
    private String cursor;

    public AccountRetentionJob(UserRepository users, AccountService accounts, AccountProperties properties, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${credit.account.retention-cron:0 15 4 * * *}", zone = "UTC")
    public void scheduled() {
        try {
            Run run = run();
            log.info("Account retention: {} checked, {} deleted, {} phone numbers removed, {} failed",
                    run.checked(), run.purged(), run.phonesRemoved(), run.failed());
        } catch (RuntimeException e) {
            log.warn("Account retention failed: {}", SafeLogging.describe(e));
        }
    }

    public synchronized Run run() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int checked = 0;
        int purged = 0;
        int phones = 0;
        int failed = 0;
        while (checked < properties.maxPerRun()) {
            UserRepository.Page page = users.accounts(cursor, Math.min(100, properties.maxPerRun() - checked));
            for (UserRepository.AccountSummary account : page.accounts()) {
                checked++;
                try {
                    if (account.lastActiveAt().isBefore(cutoff)) {
                        if (accounts.purge(account.userId(), cutoff)) {
                            purged++;
                        }
                    } else if (account.hasLegacyPhone()) {
                        users.removeLegacyPhone(account.userId());
                        phones++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Account retention: one account failed: {}", SafeLogging.describe(e));
                }
            }
            cursor = page.next();
            if (cursor == null) {
                break;
            }
        }
        return new Run(checked, purged, phones, failed);
    }
}
