package com.creditagent.web.auth.otp;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development only ({@code OTP_DELIVERY=log}): writes the code to the server log instead of
 * sending it, so the stack can be used without an AWS account. The destination is logged masked.
 * {@code OtpConfiguration} refuses this mode unless DynamoDB is a local endpoint, so it cannot be
 * switched on against real AWS by mistake.
 */
public final class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    private final OtpChannel channel;

    public LoggingOtpSender(OtpChannel channel) {
        this.channel = Objects.requireNonNull(channel);
    }

    @Override
    public void send(String destination, String code, Duration validFor) {
        log.warn("[OTP_DELIVERY=log, development only] {} sign-in code for {}: {}", channel, channel.mask(destination), code);
    }
}
