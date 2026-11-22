package com.creditagent.web.subscriptions;

/** No payment could be taken: no provider is configured, or it did not answer. Maps to 503. */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException() {
        super("Payments are not available right now. Please try again later.");
    }
}
