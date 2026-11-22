package com.creditagent.notifications;

/** A request this service will not send: answered 400. The message names fields, never their values. */
public class InvalidNotificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidNotificationException(String message) {
        super(message);
    }
}
