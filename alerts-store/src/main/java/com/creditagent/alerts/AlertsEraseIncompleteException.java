package com.creditagent.alerts;

/**
 * Erasing a person's alerts could not delete them all (or was interrupted): the caller should retry
 * the whole erase, which is idempotent.
 */
public class AlertsEraseIncompleteException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AlertsEraseIncompleteException(String message, Throwable cause) {
        super(message, cause);
    }
}
