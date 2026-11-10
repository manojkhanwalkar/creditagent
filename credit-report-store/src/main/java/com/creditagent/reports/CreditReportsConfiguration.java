package com.creditagent.reports;

import com.creditagent.common.SsnHmac;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The store, for services that {@code @Import} it. Fails at start-up if either key is missing or short. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CreditReportsProperties.class)
public class CreditReportsConfiguration {

    @Bean(destroyMethod = "close")
    DynamoDbClient creditReportsDynamoDbClient(CreditReportsProperties properties) {
        DynamoDbClient client = CreditReportsTable.client(properties.dynamodbEndpoint(), properties.region(),
                properties.dynamodbTimeout());
        if (properties.createTables()) {
            CreditReportsTable.ensure(client, properties.table());
        }
        return client;
    }

    @Bean
    CreditReportStore creditReportStore(DynamoDbClient creditReportsDynamoDbClient, CreditReportsProperties properties) {
        return new DynamoDbCreditReportStore(creditReportsDynamoDbClient, properties.table(),
                new SsnHmac(properties.ssnHashKey(), "CREDIT_FILE_HMAC_KEY"),
                new ReportCipher(properties.encryptionKey(), properties.previousEncryptionKeys()), Clock.systemUTC());
    }
}
