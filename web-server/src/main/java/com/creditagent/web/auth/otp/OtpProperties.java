package com.creditagent.web.auth.otp;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * One-time sign-in codes ({@code credit.auth.otp}).
 *
 * @param delivery {@code aws} (SES and SNS) or {@code log} (development only: the code is written to
 *     the server log; refused unless DynamoDB is a local endpoint)
 * @param region AWS region for SES and SNS
 * @param sesEndpoint override for SES, e.g. a LocalStack URL; blank = AWS
 * @param snsEndpoint override for SNS; blank = AWS
 * @param emailFrom the verified SES identity codes are emailed from
 * @param sesConfigurationSet SES configuration set for delivery events; blank = none
 * @param smsSenderId alphanumeric SMS sender id where allowed; blank = none
 * @param smsOriginationNumber registered number texts are sent from; blank = the account default
 * @param timeout longest one SES or SNS call may take, retries included; each attempt gets half
 * @param table DynamoDB table for login challenges (TTL on {@code expiresAt})
 * @param codeTtl how long a sent code works
 * @param challengeTtl how long the user has, after the password step, to finish signing in
 * @param maxAttempts wrong codes allowed per sign-in before the user must start again
 * @param maxSends codes that may be sent per sign-in (the first and its resends)
 * @param resendCooldown wait between two codes for the same sign-in
 * @param maxSendsPerUserPerHour codes sent to one account per clock hour, across all its sign-ins: caps
 *     guessing and message flooding by someone who knows the password
 */
@Validated
@ConfigurationProperties("credit.auth.otp")
public record OtpProperties(
        Delivery delivery,
        String region,
        String sesEndpoint,
        String snsEndpoint,
        @NotBlank String emailFrom,
        String sesConfigurationSet,
        String smsSenderId,
        String smsOriginationNumber,
        Duration timeout,
        @NotBlank String table,
        Duration codeTtl,
        Duration challengeTtl,
        Integer maxAttempts,
        Integer maxSends,
        Duration resendCooldown,
        Integer maxSendsPerUserPerHour) {

    public enum Delivery { AWS, LOG }

    public OtpProperties {
        delivery = delivery == null ? Delivery.AWS : delivery;
        region = region == null || region.isBlank() ? "us-east-1" : region;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        codeTtl = codeTtl == null ? Duration.ofMinutes(5) : codeTtl;
        challengeTtl = challengeTtl == null ? Duration.ofMinutes(10) : challengeTtl;
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        maxSends = maxSends == null ? 3 : maxSends;
        resendCooldown = resendCooldown == null ? Duration.ofSeconds(30) : resendCooldown;
        maxSendsPerUserPerHour = maxSendsPerUserPerHour == null ? 10 : maxSendsPerUserPerHour;
        if (maxAttempts < 1 || maxSends < 1 || maxSendsPerUserPerHour < 1) {
            throw new IllegalArgumentException("OTP attempt and send limits must be at least 1");
        }
        if (codeTtl.compareTo(challengeTtl) > 0) {
            throw new IllegalArgumentException("OTP code-ttl must not be longer than challenge-ttl");
        }
    }
}
