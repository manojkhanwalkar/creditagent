package com.creditagent.advisor.eval;

import com.creditagent.common.SafeLogging;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes each turn as one JSON line to the {@value #LOGGER} logger. {@code logback-spring.xml} sends
 * that logger, and only that logger, to its own rolling file ({@code ADVISOR_EVAL_LOG_DIR}) through an
 * asynchronous appender that drops lines rather than block the chat when the disk falls behind.
 */
public class JsonEvalLog implements EvalLog {

    public static final String LOGGER = "advisor-eval";

    private static final Logger eval = LoggerFactory.getLogger(LOGGER);
    private static final Logger log = LoggerFactory.getLogger(JsonEvalLog.class);

    private final JsonMapper json;

    public JsonEvalLog(JsonMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public void record(EvalRecord record) {
        try {
            eval.info(json.writeValueAsString(record));
        } catch (RuntimeException e) {
            // An eval line is never worth a failed chat.
            log.warn("Could not write an eval record: {}", SafeLogging.describe(e));
        }
    }
}
