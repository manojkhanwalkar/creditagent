package com.creditagent.web.notify;

import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpSender;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Sends in this process, through one {@link OtpSender} per channel (SES and SNS, or the log). */
public final class LocalNotificationGateway implements NotificationGateway {

    private final Map<OtpChannel, OtpSender> senders;

    /** @param senders one sender per channel; every channel must have one */
    public LocalNotificationGateway(Map<OtpChannel, OtpSender> senders) {
        this.senders = new EnumMap<>(senders);
        for (OtpChannel channel : OtpChannel.values()) {
            Objects.requireNonNull(this.senders.get(channel), "no sender for " + channel);
        }
    }

    @Override
    public void send(Notification n) {
        if (n.template() != Template.OTP_SIGN_IN) {
            throw new IllegalArgumentException("web-server does not send " + n.template());
        }
        senders.get(n.channel()).send(n.to(), param(n, "code"), Duration.ofMinutes(Long.parseLong(param(n, "validMinutes"))));
    }

    private static String param(Notification n, String name) {
        return Optional.ofNullable(n.params().get(name))
                .orElseThrow(() -> new IllegalArgumentException(n.template() + " needs the param " + name));
    }
}
