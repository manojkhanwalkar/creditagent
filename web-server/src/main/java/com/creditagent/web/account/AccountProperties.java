package com.creditagent.web.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Account data rights and retention ({@code credit.account.*}).
 *
 * @param reportsUrl the report generator, which erases a deleted account's stored bureau reports
 * @param timeout per try for the erase calls (alerts and reports), which are retried
 * @param retention accounts unused (no login) for this long are deleted; default two years, the GLBA
 *     disposal limit after last use
 * @param maxPerRun the most accounts one retention run looks at; the next run carries on from there
 */
@ConfigurationProperties("credit.account")
public record AccountProperties(String reportsUrl, Duration timeout, Duration retention, Integer maxPerRun) {

    public AccountProperties {
        reportsUrl = reportsUrl == null || reportsUrl.isBlank() ? "http://localhost:8083" : reportsUrl;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        retention = retention == null ? Duration.ofDays(730) : retention;
        maxPerRun = maxPerRun == null ? 1_000 : maxPerRun;
        if (retention.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalArgumentException("credit.account.retention must be at least 30 days");
        }
        if (maxPerRun < 1) {
            throw new IllegalArgumentException("credit.account.max-per-run must be at least 1");
        }
    }
}
