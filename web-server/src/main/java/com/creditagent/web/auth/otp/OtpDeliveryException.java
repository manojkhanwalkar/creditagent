package com.creditagent.web.auth.otp;

/** The email or SMS provider did not accept a sign-in code. The message never holds the code or destination. */
public class OtpDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OtpDeliveryException(OtpChannel channel, Throwable cause) {
        super("Could not send a sign-in code by " + channel, cause);
    }
}
