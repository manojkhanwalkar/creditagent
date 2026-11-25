package com.creditagent.notifications;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param lease how long a send in progress holds its key; longer than {@link #maxSendTime()}
 * @param smtpTimeout longest one SMTP send may take in all (the mail timeouts bound single socket steps)
 * @param hashKey keys the stored hashes of idempotency keys and requests, at least 32 characters; blank =
 *     derived from the service token (then rotating the token re-keys every stored hash)
 * @param smsLogCodes local sign-in only: with {@code sms=log}, also write the text, sign-in code included,
 *     to the log, so a developer without SNS can sign in by text. Refused unless {@code sms} is {@code log}
 *     and DynamoDB is local (the same guard as the log senders)
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
        Duration lease,
        Duration smtpTimeout,
        String hashKey,
        Boolean smsLogCodes) {

    public enum EmailProvider { SES, SMTP, LOG }

    public enum SmsProvider { SNS, LOG }

    @ConstructorBinding
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
        smtpTimeout = smtpTimeout == null ? Duration.ofSeconds(10) : smtpTimeout;
        hashKey = hashKey == null ? "" : hashKey.strip();
        smsLogCodes = smsLogCodes != null && smsLogCodes;
        if (smsLogCodes && sms != SmsProvider.LOG) {
            throw new IllegalArgumentException("NOTIFICATIONS_SMS_LOG_CODES needs NOTIFICATIONS_SMS=log");
        }
        if (!providerTimeout.isPositive() || !dynamodbTimeout.isPositive() || !retention.isPositive()
                || !smtpTimeout.isPositive()) {
            throw new IllegalArgumentException("credit.notifications: timeouts and retention must be positive");
        }
        if (lease.compareTo(maxSendTime(providerTimeout, smtpTimeout, dynamodbTimeout)) <= 0) {
            throw new IllegalArgumentException("credit.notifications: the lease must be longer than one send can take"
                    + " (3 x dynamodb-timeout + the longer of provider-timeout and smtp-timeout)");
        }
        if (!hashKey.isEmpty() && hashKey.length() < MIN_HASH_KEY_LENGTH) {
            throw new IllegalArgumentException("NOTIFICATIONS_HASH_KEY must be at least " + MIN_HASH_KEY_LENGTH
                    + " characters");
        }
    }

    static final int MIN_HASH_KEY_LENGTH = 32;

    /** Without {@code smsLogCodes} (off): the shape most callers and tests use. */
    public NotificationProperties(String serviceToken, EmailProvider email, SmsProvider sms, String region,
            String sesEndpoint, String snsEndpoint, String emailFrom, String sesConfigurationSet, String smsSenderId,
            String smsOriginationNumber, Duration providerTimeout, String table, String dynamodbEndpoint,
            Duration dynamodbTimeout, Boolean createTables, Duration retention, Duration lease, Duration smtpTimeout,
            String hashKey) {
        this(serviceToken, email, sms, region, sesEndpoint, snsEndpoint, emailFrom, sesConfigurationSet, smsSenderId,
                smsOriginationNumber, providerTimeout, table, dynamodbEndpoint, dynamodbTimeout, createTables, retention,
                lease, smtpTimeout, hashKey, false);
    }

    /**
     * The longest one request can take: the reserve (a put, and a read if the key is held), the provider
     * call, and recording the outcome, each DynamoDB call within its timeout.
     */
    Duration maxSendTime() {
        return maxSendTime(providerTimeout, smtpTimeout, dynamodbTimeout);
    }

    private static Duration maxSendTime(Duration provider, Duration smtp, Duration dynamodb) {
        return dynamodb.multipliedBy(3).plus(provider.compareTo(smtp) >= 0 ? provider : smtp);
    }

    /** The secret the stored hashes are keyed with: NOTIFICATIONS_HASH_KEY, else the service token. */
    String hashSecret() {
        return hashKey.isEmpty() ? serviceToken : hashKey;
    }

    /** True when either channel writes to the log instead of sending. */
    boolean logsAnything() {
        return email == EmailProvider.LOG || sms == SmsProvider.LOG;
    }

    @Override
    public String toString() {
        return "NotificationProperties[serviceToken=<redacted>, hashKey=<redacted>, email=" + email + ", sms=" + sms + ", region=" + region
                + ", table=" + table + ", smsLogCodes=" + smsLogCodes + "]";
    }
}
