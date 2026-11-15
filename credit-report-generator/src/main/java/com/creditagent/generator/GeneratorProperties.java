package com.creditagent.generator;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The generator's own settings. Where reports are stored is {@code credit.reports.*}
 * ({@link com.creditagent.reports.CreditReportsProperties}).
 *
 * @param seedSsns SSNs to generate reports for at start-up (demo data only)
 * @param serviceToken the internal service token ({@code INTERNAL_API_TOKEN}) the erase API requires;
 *     blank turns the internal API off (404)
 * @param retention stored reports not updated for this long are purged (default two years, the GLBA
 *     disposal limit after last use)
 * @param purgeMaxPerRun the most report rows one retention run deletes; the rest wait for the next run
 */
@ConfigurationProperties("credit.generator")
public record GeneratorProperties(List<String> seedSsns, String serviceToken, Duration retention, Integer purgeMaxPerRun) {

    static final Duration DEFAULT_RETENTION = Duration.ofDays(730);
    static final int DEFAULT_PURGE_MAX = 10_000;

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GeneratorProperties {
        seedSsns = seedSsns == null ? List.of() : List.copyOf(seedSsns);
        serviceToken = serviceToken == null ? "" : serviceToken;
        retention = retention == null ? DEFAULT_RETENTION : retention;
        purgeMaxPerRun = purgeMaxPerRun == null ? DEFAULT_PURGE_MAX : purgeMaxPerRun;
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("credit.generator.retention must be positive");
        }
        if (purgeMaxPerRun < 1) {
            throw new IllegalArgumentException("credit.generator.purge-max-per-run must be at least 1");
        }
    }

    /** Just the seed SSNs, for tests and callers that predate the rest. */
    public GeneratorProperties(List<String> seedSsns) {
        this(seedSsns, null, null, null);
    }

    @Override
    public String toString() {
        return "GeneratorProperties[seedSsns=" + seedSsns.size() + ", serviceToken=<redacted>, retention=" + retention
                + ", purgeMaxPerRun=" + purgeMaxPerRun + "]";
    }
}
