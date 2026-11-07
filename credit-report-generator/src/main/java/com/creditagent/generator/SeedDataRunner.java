package com.creditagent.generator;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Generates demo reports for {@code credit.files.seed-ssns} at start-up. */
@Component
class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private final GeneratorProperties properties;
    private final CreditReportService service;

    SeedDataRunner(GeneratorProperties properties, CreditReportService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String raw : properties.seedSsns()) {
            try {
                service.generate(Ssn.parse(raw), null);
            } catch (InvalidSsnException e) {
                log.warn("Skipping invalid seed SSN: {}", e.getMessage());
            }
        }
        if (!properties.seedSsns().isEmpty()) {
            log.info("Seeded {} demo credit reports into {}", properties.seedSsns().size(), properties.directory());
        }
    }
}
