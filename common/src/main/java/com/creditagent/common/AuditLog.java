package com.creditagent.common;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accountability records for access to and changes of personal data: who read whose credit report,
 * who locked it, who exported or deleted an account (GLBA Safeguards Rule 314.4(c)(8); what a breach
 * investigation needs to know whose data was reached).
 *
 * <p>Every event is one line on the dedicated {@value #LOGGER} logger, outside every package, so the
 * deployment can route it to an append-only sink with its own retention (at least a year) apart from
 * the application logs. A line is {@code key=value} pairs, always in the same order:
 * {@code event actor subject outcome at}, then the details. Values are restricted to a safe alphabet
 * and length, so user-controlled text can neither forge a second record (CR/LF) nor smuggle personal
 * data in: callers pass ids and keyed references, never an SSN, an email or a report.
 */
public final class AuditLog {

    /** The logger name; route it to the audit sink. */
    public static final String LOGGER = "audit";

    /** What happened. */
    public enum Event {
        /** A credit report was read (subject: a keyed SSN reference or a user id). */
        REPORT_READ,
        /** A user locked or unlocked their credit report. */
        REPORT_LOCK_CHANGED,
        /** A user downloaded the data held about them. */
        ACCOUNT_EXPORTED,
        /** A user deleted their account and data. */
        ACCOUNT_DELETED,
        /** An inactive account was deleted by the retention job. */
        ACCOUNT_PURGED,
        /** A consumer's stored bureau reports were erased. */
        REPORTS_ERASED,
        /** Stored bureau reports past their retention were purged. */
        REPORTS_PURGED,
        /** A user's dark-web alerts were erased. */
        ALERTS_ERASED,
        /** A user authorised the Claude connector. */
        CONNECTOR_GRANT_OPENED,
        /** A connector grant was revoked. */
        CONNECTOR_GRANT_REVOKED,
        /** A user passed the password and one-time-code steps and a session was opened. */
        LOGIN_SUCCEEDED,
        /** A one-time sign-in code was sent (detail: the channel). */
        LOGIN_CODE_SENT,
        /** A one-time sign-in code was wrong, expired or used up its attempts. */
        LOGIN_CODE_FAILED
    }

    static final int MAX_VALUE_LENGTH = 96;

    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    private final Logger sink;
    private final Clock clock;

    /** Writes to the {@value #LOGGER} logger with the system UTC clock. */
    public AuditLog() {
        this(log, Clock.systemUTC());
    }

    AuditLog(Logger sink, Clock clock) {
        this.sink = Objects.requireNonNull(sink);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Records an event.
     *
     * @param actor who did it, e.g. {@code user:ada}, {@code service:web-server}, {@code job:retention}
     * @param subject whose data, e.g. {@code user:ada} or {@code ssnref:1a2b…} (a keyed reference, never the SSN)
     * @param outcome e.g. {@code OK}, {@code NOT_FOUND}, {@code REFUSED}, {@code FAILED}
     * @param details further ids or counts; values are sanitised like the rest
     */
    public void record(Event event, String actor, String subject, String outcome, Map<String, ?> details) {
        sink.info(format(event, actor, subject, outcome, clock.instant(), details));
    }

    /** {@link #record} without details. */
    public void record(Event event, String actor, String subject, String outcome) {
        record(event, actor, subject, outcome, Map.of());
    }

    static String format(Event event, String actor, String subject, String outcome, Instant at, Map<String, ?> details) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", Objects.requireNonNull(event).name());
        fields.put("actor", actor);
        fields.put("subject", subject);
        fields.put("outcome", outcome);
        fields.put("at", at);
        if (details != null) {
            details.forEach((key, value) -> fields.putIfAbsent(clean(key), value));
        }
        StringBuilder line = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(key).append('=').append(clean(value == null ? "-" : value.toString()));
        });
        return line.toString();
    }

    /** Letters, digits and {@code . _ : @ # / -} only; anything else becomes {@code _}; capped in length. */
    static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_VALUE_LENGTH));
        for (int i = 0; i < value.length() && out.length() < MAX_VALUE_LENGTH; i++) {
            char c = value.charAt(i);
            boolean safe = c < 128 && (Character.isLetterOrDigit(c) || ".:_@#/-".indexOf(c) >= 0);
            out.append(safe ? c : '_');
        }
        return out.toString();
    }
}
