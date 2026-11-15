package com.creditagent.web.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.ReportStatus;
import com.creditagent.web.auth.User;
import com.creditagent.web.auth.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AccountRetentionJobTest {

    static final Instant NOW = Instant.parse("2026-11-14T04:15:00Z");
    final InMemoryRepositories.Users users = new InMemoryRepositories.Users();
    final AccountService accounts = mock(AccountService.class);

    User user(String id, Instant created, Instant lastLogin) {
        return new User(id, "$2a$h", id + "@example.com", "h-" + id, "0000", "k", created, ReportStatus.UNLOCKED,
                null, null, null, null, null, lastLogin);
    }

    AccountRetentionJob job(int maxPerRun) {
        return new AccountRetentionJob(users, accounts, new AccountProperties(null, null, null, maxPerRun),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void inactiveAccountsArePurgedActiveOnesKeptAndLegacyPhonesRemoved() {
        users.byId.put("idle", user("idle", NOW.minus(Duration.ofDays(900)), NOW.minus(Duration.ofDays(731))));
        users.byId.put("new", user("new", NOW.minus(Duration.ofDays(900)), NOW.minus(Duration.ofDays(1))));
        users.byId.put("never", user("never", NOW.minus(Duration.ofDays(800)), null));
        users.byId.put("fresh", user("fresh", NOW.minus(Duration.ofDays(3)), null));
        users.legacyPhones.add("new");
        given(accounts.purge(org.mockito.ArgumentMatchers.eq("idle"), org.mockito.ArgumentMatchers.any())).willReturn(true);
        given(accounts.purge(org.mockito.ArgumentMatchers.eq("never"), org.mockito.ArgumentMatchers.any())).willReturn(false); // deleted meanwhile

        AccountRetentionJob.Run run = job(1000).run();

        assertThat(run).isEqualTo(new AccountRetentionJob.Run(4, 1, 1, 0));
        org.mockito.Mockito.verify(accounts).purge("idle", NOW.minus(Duration.ofDays(730)));
        org.mockito.Mockito.verify(accounts).purge("never", NOW.minus(Duration.ofDays(730)));
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.never()).purge("new", NOW.minus(Duration.ofDays(730)));
        assertThat(users.legacyPhones).isEmpty();
    }

    @Test
    void aRunIsBoundedAndTheNextOneCarriesOnFromItsPlace() {
        for (int i = 0; i < 5; i++) {
            users.byId.put("u" + i, user("u" + i, NOW, null));
            users.legacyPhones.add("u" + i);
        }
        AccountRetentionJob job = job(2);
        assertThat(job.run().checked()).isEqualTo(2);
        assertThat(users.legacyPhones).containsExactlyInAnyOrder("u2", "u3", "u4");
        job.run();
        assertThat(job.run().checked()).isEqualTo(1); // the last page, then it starts over
        assertThat(users.legacyPhones).isEmpty();
        assertThat(job.run().checked()).isEqualTo(2);
    }

    @Test
    void oneFailingAccountDoesNotStopTheRunAndTheSchedulerNeverThrows() {
        users.byId.put("a", user("a", NOW.minus(Duration.ofDays(1000)), null));
        users.byId.put("b", user("b", NOW.minus(Duration.ofDays(1000)), null));
        given(accounts.purge(org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any())).willThrow(new IllegalStateException("alerts down"));
        given(accounts.purge(org.mockito.ArgumentMatchers.eq("b"), org.mockito.ArgumentMatchers.any())).willReturn(true);
        assertThat(job(10).run()).isEqualTo(new AccountRetentionJob.Run(2, 1, 0, 1));

        UserRepository broken = mock(UserRepository.class);
        given(broken.accounts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .willThrow(new IllegalStateException("dynamo down"));
        new AccountRetentionJob(broken, accounts, new AccountProperties(null, null, null, null), Clock.systemUTC()).scheduled();
        job(10).scheduled();
    }

    @Test
    void propertiesHaveDefaultsAndRefuseNonsense() {
        AccountProperties defaults = new AccountProperties(" ", null, null, null);
        assertThat(defaults.reportsUrl()).isEqualTo("http://localhost:8083");
        assertThat(defaults.retention()).isEqualTo(Duration.ofDays(730));
        assertThat(defaults.maxPerRun()).isEqualTo(1000);
        assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> new AccountProperties(null, null, Duration.ofDays(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountProperties(null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
