package com.creditagent.notifications;

/** Delivers one rendered message on one channel. */
@FunctionalInterface
public interface Sender {

    /**
     * @param to the email address or E.164 number, already validated
     * @return the provider's message id (null when it gives none)
     * @throws DeliveryException if the provider did not accept the message
     */
    String send(String to, Message message);
}
