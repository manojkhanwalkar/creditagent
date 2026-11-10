package com.creditagent.generator;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The generator's own settings. Where reports are stored is {@code credit.reports.*}
 * ({@link com.creditagent.reports.CreditReportsProperties}).
 *
 * @param seedSsns SSNs to generate reports for at start-up (demo data only)
 */
@ConfigurationProperties("credit.generator")
public record GeneratorProperties(List<String> seedSsns) {

    public GeneratorProperties {
        seedSsns = seedSsns == null ? List.of() : List.copyOf(seedSsns);
    }

    @Override
    public String toString() {
        return "GeneratorProperties[seedSsns=" + seedSsns.size() + "]";
    }
}
