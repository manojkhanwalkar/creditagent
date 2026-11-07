package com.creditagent.common;

/** Thrown when an SSN fails validation. The message never echoes the submitted value. */
public class InvalidSsnException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidSsnException(String message) {
        super(message);
    }
}
