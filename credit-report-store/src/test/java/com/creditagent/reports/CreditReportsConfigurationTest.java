package com.creditagent.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The wiring services import: a store with both keys, and a refusal to start without them. */
class CreditReportsConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CreditReportsConfiguration.class)
            .withConfiguration(AutoConfigurations.of())
            .withPropertyValues("credit.reports.dynamodb-endpoint=http://localhost:1", "aws.accessKeyId=local");

    @Test
    void bothKeysGiveAStore() {
        runner.withPropertyValues("credit.reports.ssn-hash-key=" + "h".repeat(32), "credit.reports.encryption-key=" + "e".repeat(32))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CreditReportStore.class)).isInstanceOf(DynamoDbCreditReportStore.class);
                });
    }

    @Test
    void aMissingOrShortKeyStopsTheService() {
        runner.withPropertyValues("credit.reports.ssn-hash-key=" + "h".repeat(32)).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("credit.reports.ssn-hash-key=short", "credit.reports.encryption-key=" + "e".repeat(32))
                .run(context -> assertThat(context).hasFailed());
    }
}
