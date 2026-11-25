package com.creditagent.web.subscriptions;

/** No account can hold the term: the user does not exist or has no email. Maps to 404. */
public class UnknownSubscriberException extends RuntimeException {

    public UnknownSubscriberException() {
        super("No such user.");
    }
}
