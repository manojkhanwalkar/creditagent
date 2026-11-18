package com.creditagent.web.auth.otp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Emails sign-in codes through Amazon SES (API v2). The client carries the timeouts and retry policy
 * (see {@code OtpConfiguration}); a retried send can at worst deliver the same code twice.
 */
public final class SesEmailOtpSender implements OtpSender {

    static final String SUBJECT = "Your CreditAgent sign-in code";

    private final SesV2Client ses;
    private final String from;
    private final String configurationSet;

    /**
     * @param from the verified SES identity the email comes from
     * @param configurationSet SES configuration set for delivery events, or null/blank for none
     */
    public SesEmailOtpSender(SesV2Client ses, String from, String configurationSet) {
        this.ses = Objects.requireNonNull(ses);
        this.from = Objects.requireNonNull(from);
        this.configurationSet = configurationSet == null || configurationSet.isBlank() ? null : configurationSet;
    }

    @Override
    public void send(String destination, String code, Duration validFor) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(d -> d.toAddresses(destination))
                .content(c -> c.simple(m -> m
                        .subject(utf8(SUBJECT))
                        .body(b -> b.text(utf8(OtpSender.text(code, validFor, false))))))
                .configurationSetName(configurationSet)
                .build();
        try {
            ses.sendEmail(request);
        } catch (SdkException e) {
            throw new OtpDeliveryException(OtpChannel.EMAIL, e);
        }
    }

    private static Content utf8(String data) {
        return Content.builder().data(data).charset(StandardCharsets.UTF_8.name()).build();
    }
}
