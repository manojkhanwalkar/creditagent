package com.creditagent.nrg;

/** A bureau report could not be read. The message names the field, never its value. */
class InvalidBureauReportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidBureauReportException(String message) {
        super(message);
    }
}
