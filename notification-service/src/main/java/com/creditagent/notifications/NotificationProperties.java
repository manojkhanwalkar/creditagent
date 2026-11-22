package com.creditagent.notifications;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The notification service ({@code credit.notifications.*}).
 *
 * @param serviceToken the shared internal token callers present; blank = the API is off (404)
 * @param email {@code ses}, {@code smtp} or {@code log} (development only)
 * @param sms {@code sns} or {@code log} (development only)
 * @param region AWS region for SES, SNS and DynamoDB
 * @param sesEndpoint SES override (an emulator); blank = AWS
 * @param snsEndpoint SNS override; blank = AWS
 * @param emailFrom the sender address (a verified SES identity for {@code ses})
 * @param sesConfigurationSet SES configuration set for delivery events; blank = none
 * @param smsSenderId alphanumeric SMS sender id where allowed; blank = none
 * @param smsOriginationNumber the registered number texts are sent from; blank = the account default
 * @param providerTimeout longest one SES or SNS call may take, the SDK's retries included
 * @param table the DynamoDB table of accepted sends (TTL on {@code expiresAt})
 * @param dynamodbEndpoint blank = AWS; {@code http://localhost:8000} for DynamoDB Local
 * @param dynamodbTimeout longest one DynamoDB call may take, retries included
 * @param createTables create the table if missing (local and test only)
 * @param retention how long an accepted key is remembered
 * @param lease how long a send in progress holds its key; longer than the provider timeout
 */
@ConfigurationProperties("credit.notifications")
public record NotificationProperties(
        String serviceToken,
        EmailProvider email,
        SmsProvider sms,
        String region,
        String sesEndpoint,
        String snsEndpoint,
        String emailFrom,
        String sesConfigurationSet,
        String smsSenderId,
        String smsOriginationNumber,
        Duration providerTimeout,
        String table,
        String dynamodbEndpoint,
        Duration dynamodbTimeout,
        Boolean createTables,
        Duration retention,
        Duration lease) {

    public enum EmailProvider { SES, SMTP, LOG }

    public enum SmsProvider { SNS, LOG }

    public NotificationProperties {
        serviceToken = serviceToken == null ? "" : serviceToken;
        email = email == null ? EmailProvider.SES : email;
        sms = sms == null ? SmsProvider.SNS : sms;
        region = region == null || region.isBlank() ? "us-east-1" : region;
        emailFrom = emailFrom == null || emailFrom.isBlank() ? "CreditAgent <no-reply@creditagent.example>" : emailFrom.strip();
        providerTimeout = providerTimeout == null ? Duration.ofSeconds(4) : providerTimeout;
        table = table == null || table.isBlank() ? "credit-agent-notifications" : table;
        dynamodbTimeout = dynamodbTimeout == null ? Duration.ofSeconds(2) : dynamodbTimeout;
        createTables = createTables != null && createTables;
        retention = retention == null ? Duration.ofDays(30) : retention;
        lease = lease == null ? Duration.ofSeconds(60) : lease;
        if (!providerTimeout.isPositive() || !dynamodbTimeout.isPositive() || !retention.isPositive()
                || lease.compareTo(providerTimeout.plus(dynamodbTimeout)) <= 0) {
            throw new IllegalArgumentException("credit.notifications: timeouts and retention must be positive, and the"
                    + " lease longer than provider-timeout plus dynamodb-timeout");
        }
    }

    /** True when either channel writes to the log instead of sending. */
    boolean logsAnything() {
        return email == EmailProvider.LOG || sms == SmsProvider.LOG;
    }

    @Override
    public String toString() {
        return "NotificationProperties[serviceToken=<redacted>, email=" + email + ", sms=" + sms + ", region=" + region
                + ", table=" + table + "]";
    }
}
