package com.creditagent.web.auth.otp;

import java.time.Duration;

/** Delivers one sign-in code on one channel. */
@FunctionalInterface
public interface OtpSender {

    /**
     * @param destination the account's email address or E.164 mobile number
     * @param code the six-digit code
     * @param validFor how long the code works, for the message text
     * @throws OtpDeliveryException if the provider did not accept the message
     */
    void send(String destination, String code, Duration validFor);

    /** The email or text message body, the same on every channel apart from its length. */
    static String text(String code, Duration validFor, boolean sms) {
        long minutes = Math.max(1, validFor.toMinutes());
        if (sms) {
            return code + " is your CreditAgent sign-in code. It expires in " + minutes
                    + " minutes. Never share it; we will never ask for it.";
        }
        return "Your CreditAgent sign-in code is " + code + ".\n\n"
                + "It expires in " + minutes + " minutes. Never share it: CreditAgent will never ask you for it.\n\n"
                + "If you did not just try to sign in, someone else knows your password. Change it, and do not"
                + " enter this code anywhere.\n";
    }
}
