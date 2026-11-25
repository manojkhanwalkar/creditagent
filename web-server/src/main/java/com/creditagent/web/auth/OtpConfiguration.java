package com.creditagent.web.auth;

import com.creditagent.clients.RetrySettings;
import com.creditagent.common.AuditLog;
import com.creditagent.web.auth.otp.OtpProperties;
import com.creditagent.web.notify.NotificationGateway;
import com.creditagent.web.notify.NotificationsProperties;
import com.creditagent.web.notify.RemoteNotificationGateway;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * One-time sign-in codes: the challenge store, the notification-service that sends the codes, and
 * {@link OtpLoginService}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OtpProperties.class, NotificationsProperties.class})
class OtpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OtpConfiguration.class);

    @Bean
    OtpChallengeRepository otpChallengeRepository(DynamoDbClient dynamo, AuthProperties auth, OtpProperties otp) {
        if (auth.dynamodb().createTables()) {
            DynamoDbTables.ensureWithTtl(dynamo, otp.table(), DynamoDbOtpChallengeRepository.KEY,
                    DynamoDbOtpChallengeRepository.TTL_ATTRIBUTE);
        }
        return new DynamoDbOtpChallengeRepository(dynamo, otp.table());
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

    /** Codes are sent through the notification-service; sign-in needs it (a clear 503 when it is down). */
    @Bean
    NotificationGateway notificationGateway(NotificationsProperties notifications, RestClient.Builder builder,
            RetrySettings retry, @Value("${credit.internal.token:}") String serviceToken) {
        log.info("Sign-in codes are sent through the notification-service at {}", notifications.url());
        return RemoteNotificationGateway.create(builder, notifications.url(), notifications.connectTimeout(),
                notifications.timeout(), retry, serviceToken);
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
