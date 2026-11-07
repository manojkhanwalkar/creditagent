package com.creditagent.clients;

/** A downstream service (MCP server, score calculator) failed or could not be reached. */
public class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
