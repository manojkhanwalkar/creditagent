package com.creditagent.web.subscriptions;

/** The payment provider refused the payment. Maps to 402. */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException() {
        super("The payment was declined. No subscription was started.");
    }
}
