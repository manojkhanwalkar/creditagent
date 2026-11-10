package com.creditagent.advisor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonEvalLogTest {

    final JsonMapper json = JsonMapper.builder().build();
    final Logger logger = (Logger) LoggerFactory.getLogger(JsonEvalLog.LOGGER);
    final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
    }

    @Test
    void writesOneJsonLinePerTurn() {
        new JsonEvalLog(json).record(new EvalRecord(Instant.parse("2026-11-09T12:00:00Z"), "conv-1", 2,
                EvalRecord.safe("My SSN is 123-45-6789, why is my score low?"), "ALLOWED",
                EvalRecord.safe("Call 415-555-0100."), "shown", "PASSED", "PlaceholderAdvisorModel", 7));
        assertThat(lines.list).hasSize(1);
        String line = lines.list.getFirst().getFormattedMessage();
        assertThat(line).doesNotContain("\n").doesNotContain("6789").doesNotContain("555-0100");
        JsonNode node = json.readTree(line);
        assertThat(node.path("conversation").asString()).isEqualTo("conv-1");
        assertThat(node.path("turn").intValue()).isEqualTo(2);
        assertThat(node.path("question").asString()).isEqualTo("My SSN is [SSN removed], why is my score low?");
        assertThat(node.path("modelAnswer").asString()).isEqualTo("Call [phone removed].");
        assertThat(node.path("outputVerdict").asString()).isEqualTo("PASSED");
        assertThat(node.path("latencyMillis").intValue()).isEqualTo(7);
        assertThat(node.has("at")).isTrue();
    }

    @Test
    void aFailureToWriteNeverReachesTheChat() {
        JsonMapper broken = org.mockito.Mockito.mock(JsonMapper.class);
        org.mockito.BDDMockito.given(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .willThrow(new IllegalStateException("boom"));
        new JsonEvalLog(broken).record(new EvalRecord(Instant.EPOCH, "c", 0, null, "EMPTY", null, "r", null, "m", 0));
        assertThat(lines.list).isEmpty();
        assertThat(EvalRecord.safe(null)).isNull();
        EvalLog.NONE.record(null);
        assertThat(new EvalRecord(Instant.EPOCH, "c", 0, "secret 123-45-6789", "ALLOWED", null, "r", "PASSED", "m", 0)
                .toString()).doesNotContain("secret");
    }
}
