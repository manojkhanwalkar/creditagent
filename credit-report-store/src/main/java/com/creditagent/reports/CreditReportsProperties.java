package com.creditagent.reports;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and how credit reports are stored ({@code credit.reports.*}); the same for the generator,
 * which writes them, and the MCP server, which reads them.
 *
 * @param ssnHashKey keys the SSN hash in the {@code ssn} column (CREDIT_FILE_HMAC_KEY); the same in both
 * @param encryptionKey encrypts new reports (CREDIT_REPORT_ENCRYPTION_KEY); the same in both
 * @param previousEncryptionKeys still decrypt reports written before a key rotation
 * @param table the table name
 * @param dynamodbEndpoint blank = AWS; {@code http://localhost:8000} for DynamoDB Local
 * @param createTables create the table if missing (local and test only)
 */
@ConfigurationProperties("credit.reports")
public record CreditReportsProperties(
        String ssnHashKey,
        String encryptionKey,
        List<String> previousEncryptionKeys,
        String table,
        String dynamodbEndpoint,
        String region,
        Duration dynamodbTimeout,
        Boolean createTables) {

    public CreditReportsProperties {
        previousEncryptionKeys = previousEncryptionKeys == null ? List.of()
                : previousEncryptionKeys.stream().filter(k -> k != null && !k.isBlank()).toList();
        table = table == null || table.isBlank() ? "CreditReports" : table;
        region = region == null || region.isBlank() ? "us-east-1" : region;
        dynamodbTimeout = dynamodbTimeout == null ? Duration.ofSeconds(5) : dynamodbTimeout;
        createTables = createTables != null && createTables;
    }

    @Override
    public String toString() {
        return "CreditReportsProperties[ssnHashKey=<redacted>, encryptionKey=<redacted>, previousEncryptionKeys="
                + previousEncryptionKeys.size() + ", table=" + table + ", dynamodbEndpoint=" + dynamodbEndpoint
                + ", region=" + region + ", dynamodbTimeout=" + dynamodbTimeout + ", createTables=" + createTables + "]";
    }
}
