package com.creditagent.web.notify;

import com.creditagent.web.auth.otp.OtpChannel;
import java.util.Map;
import java.util.Objects;

/** Sends one templated message: the seam for the coming notification-service (docs/adr/0001). */
public interface NotificationGateway {

    /** @throws com.creditagent.web.auth.otp.OtpDeliveryException if the provider did not accept it */
    void send(Notification notification);

    /** What a message says; the sender renders its text from the params. */
    enum Template {
        /** A sign-in code. Params: {@code code}, {@code validMinutes}. */
        OTP_SIGN_IN,
        /** A dark-web exposure alert. Declared for the service contract; web-server does not send it. */
        DARK_WEB_ALERT
    }

    /** One message; {@link #toString} hides {@code to}, the param values (a code) and the key (a challenge id). */
    record Notification(OtpChannel channel, String to, Template template, Map<String, String> params,
            String idempotencyKey) {
        public Notification {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(template, "template");
            params = Map.copyOf(params);
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }

        @Override
        public String toString() {
            return "Notification[channel=" + channel + ", template=" + template + ", to=<redacted>, params="
                    + new java.util.TreeSet<>(params.keySet()) + ", idempotencyKey=<redacted>]";
        }
    }
}
