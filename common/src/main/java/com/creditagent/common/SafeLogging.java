package com.creditagent.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes an exception without its messages.
 *
 * <p>Third-party exceptions (HTTP, MCP, A2A clients) may put request content, and therefore an SSN,
 * into their messages. Log {@code SafeLogging.describe(e)} instead of the exception itself when the
 * failure happened while handling personal data.
 */
public final class SafeLogging {

    private static final int MAX_DEPTH = 8;

    private SafeLogging() {}

    /** e.g. {@code ServiceUnavailableException <- ExecutionException <- ConnectException}. */
    public static String describe(Throwable t) {
        List<String> names = new ArrayList<>();
        for (Throwable c = t; c != null && names.size() < MAX_DEPTH; c = c.getCause()) {
            names.add(c.getClass().getSimpleName());
            if (c.getCause() == c) {
                break;
            }
        }
        return String.join(" <- ", names);
    }
}
