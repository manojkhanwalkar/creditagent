package com.creditagent.notifications;

import jakarta.mail.SendFailedException;
import java.util.regex.Pattern;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * The provider did not confirm a message. The exception message never holds the destination or text.
 *
 * <p>{@link #refused()} is true only when the provider clearly rejected the message, so it was certainly
 * not delivered (answered 422). Anything else (a timeout, a 5xx, throttling, a dropped connection) may
 * still have delivered it (answered 503 with {@code Retry-After}).
 */
public class DeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Error names that mean "slow down" or "over a limit", not "this message is invalid". */
    private static final Pattern THROTTLED = Pattern.compile("(?i)throttl|limit|toomany|slowdown");

    private final boolean refused;

    public DeliveryException(Channel channel, Throwable cause, boolean refused) {
        super("Could not send by " + channel, cause);
        this.refused = refused;
    }

    /** An SES or SNS failure, classified by {@link #clearlyRefused}. */
    static DeliveryException fromAws(Channel channel, Throwable cause) {
        return new DeliveryException(channel, cause, clearlyRefused(cause));
    }

    /** An SMTP failure, classified by {@link #mailRefused}. */
    static DeliveryException fromMail(MailException cause) {
        return new DeliveryException(Channel.EMAIL, cause, mailRefused(cause));
    }

    /** True when the message was certainly not delivered. */
    public boolean refused() {
        return refused;
    }

    /**
     * A 4xx from SES or SNS that is not throttling or a limit: the message was not accepted. SNS answers
     * {@code Throttled}, which the SDK's own throttling check does not know, so names are checked too.
     */
    static boolean clearlyRefused(Throwable cause) {
        if (!(cause instanceof SdkServiceException service)
                || service.statusCode() < 400 || service.statusCode() >= 500 || service.isThrottlingException()) {
            return false;
        }
        String code = service instanceof AwsServiceException aws && aws.awsErrorDetails() != null
                && aws.awsErrorDetails().errorCode() != null ? aws.awsErrorDetails().errorCode() : "";
        return !THROTTLED.matcher(code).find() && !THROTTLED.matcher(cause.getClass().getSimpleName()).find();
    }

    /**
     * SMTP: a message that could not be built, or a relay that rejected every recipient outright (a 5xx
     * answer to RCPT, which Jakarta Mail reports as invalid addresses with none sent), was certainly not
     * delivered. A 4xx ("try later"), a lost connection, a login failure or a timeout is unknown.
     */
    static boolean mailRefused(MailException cause) {
        if (cause instanceof MailParseException || cause instanceof MailPreparationException) {
            return true;
        }
        if (!(cause instanceof MailSendException send)) {
            return false;
        }
        Throwable[] failures = send.getMessageExceptions();
        if (failures.length == 0) {
            return false;
        }
        for (Throwable failure : failures) {
            if (!(failure instanceof SendFailedException rejected) || !rejectedOutright(rejected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rejectedOutright(SendFailedException e) {
        return e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0
                && (e.getValidSentAddresses() == null || e.getValidSentAddresses().length == 0)
                && (e.getValidUnsentAddresses() == null || e.getValidUnsentAddresses().length == 0);
    }
}
