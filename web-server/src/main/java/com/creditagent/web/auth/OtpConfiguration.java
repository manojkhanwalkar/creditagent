package com.creditagent.web.auth;

import com.creditagent.common.AuditLog;
import com.creditagent.web.auth.otp.LoggingOtpSender;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpProperties;
import com.creditagent.web.auth.otp.OtpSender;
import com.creditagent.web.auth.otp.SesEmailOtpSender;
import com.creditagent.web.auth.otp.SnsSmsOtpSender;
import com.creditagent.web.notify.LocalNotificationGateway;
import com.creditagent.web.notify.NotificationGateway;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;

/** One-time sign-in codes: the challenge store, the SES and SNS senders, and {@link OtpLoginService}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtpProperties.class)
class OtpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OtpConfiguration.class);

    static final String DELIVERY = "credit.auth.otp.delivery";

    @Bean
    OtpChallengeRepository otpChallengeRepository(DynamoDbClient dynamo, AuthProperties auth, OtpProperties otp) {
        if (auth.dynamodb().createTables()) {
            DynamoDbTables.ensureWithTtl(dynamo, otp.table(), DynamoDbOtpChallengeRepository.KEY,
                    DynamoDbOtpChallengeRepository.TTL_ATTRIBUTE);
        }
        return new DynamoDbOtpChallengeRepository(dynamo, otp.table());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = DELIVERY, havingValue = "aws", matchIfMissing = true)
    SesV2Client sesClient(OtpProperties otp) {
        SesV2ClientBuilder builder = SesV2Client.builder()
                .region(Region.of(otp.region()))
                .overrideConfiguration(overrides(otp.timeout()));
        if (otp.sesEndpoint() != null && !otp.sesEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(otp.sesEndpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = DELIVERY, havingValue = "aws", matchIfMissing = true)
    SnsClient snsClient(OtpProperties otp) {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(otp.region()))
                .overrideConfiguration(overrides(otp.timeout()));
        if (otp.snsEndpoint() != null && !otp.snsEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(otp.snsEndpoint()));
        }
        return builder.build();
    }

    /**
     * Bounded calls with the SDK's standard retry mode (up to 3 attempts, exponential backoff with
     * jitter, on throttling, 5xx and connection errors only), all within {@code timeout}. A retried send
     * can deliver the same code twice, which is harmless; the code is not changed between attempts.
     */
    static ClientOverrideConfiguration overrides(Duration timeout) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(timeout)
                .apiCallAttemptTimeout(timeout.dividedBy(2))
                .retryStrategy(RetryMode.STANDARD)
                .build();
    }

    /** SES and SNS, or (development only) the log. */
    static Map<OtpChannel, OtpSender> senders(OtpProperties otp, AuthProperties auth, SesV2Client ses, SnsClient sns) {
        Map<OtpChannel, OtpSender> senders = new EnumMap<>(OtpChannel.class);
        if (otp.delivery() == OtpProperties.Delivery.LOG) {
            requireLocal(auth);
            log.warn("OTP_DELIVERY=log: sign-in codes are written to this log and not sent. Development only.");
            for (OtpChannel channel : OtpChannel.values()) {
                senders.put(channel, new LoggingOtpSender(channel));
            }
            return senders;
        }
        senders.put(OtpChannel.EMAIL, new SesEmailOtpSender(ses, otp.emailFrom(), otp.sesConfigurationSet()));
        senders.put(OtpChannel.SMS, new SnsSmsOtpSender(sns, otp.smsSenderId(), otp.smsOriginationNumber()));
        return senders;
    }

    /**
     * Codes in the log would let anyone who reads it sign in, so log delivery is allowed only against a
     * DynamoDB on this machine or in compose, never against AWS (an AWS, VPC or FIPS endpoint override
     * included).
     */
    static void requireLocal(AuthProperties auth) {
        if (!auth.dynamodb().local()) {
            throw new IllegalStateException("OTP_DELIVERY=log is for local development only and needs DYNAMODB_ENDPOINT"
                    + " on localhost or the compose dynamodb service");
        }
    }

    /** A key for code hashes derived from the JWT secret, so no new secret is needed and the two never match. */
    static byte[] codeKey(String jwtSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("credit-agent/otp-code-key/v1".getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    /** Sends in this process through the SES and SNS (or log) senders. */
    @Bean
    NotificationGateway notificationGateway(OtpProperties otp, AuthProperties auth, ObjectProvider<SesV2Client> ses,
            ObjectProvider<SnsClient> sns) {
        return new LocalNotificationGateway(senders(otp, auth, ses.getIfAvailable(), sns.getIfAvailable()));
    }

    @Bean
    OtpLoginService otpLoginService(AuthService authService, OtpChallengeRepository challenges,
            OtpProperties otp, AuthProperties auth, NotificationGateway notifications, AuditLog audit) {
        OtpLoginService.Limits limits = new OtpLoginService.Limits(otp.codeTtl(), otp.challengeTtl(), otp.maxAttempts(),
                otp.maxSends(), otp.resendCooldown(), otp.maxSendsPerUserPerHour());
        return new OtpLoginService(authService, challenges, notifications, limits, codeKey(auth.jwtSecret()),
                Clock.systemUTC(), audit);
    }
}
