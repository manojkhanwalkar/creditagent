package com.creditagent.web.auth;

/** 403: a signed-in user asked for a credit report that is not their own. */
public class NotOwnSsnException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = "You can only look up your own credit report.";

    public NotOwnSsnException() {
        super(MESSAGE);
    }
}
