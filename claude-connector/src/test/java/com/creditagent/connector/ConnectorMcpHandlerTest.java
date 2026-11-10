package com.creditagent.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.creditagent.common.CreditAdvice;
import com.creditagent.common.Recommendation;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConnectorMcpHandlerTest {

    static final ConnectorUser USER = new ConnectorUser("avery", Ssn.parse("123-45-6789"), "token", "https://c/mcp", "credit");

    final JsonMapper mapper = JsonMapper.builder().build();
    final CreditTools tools = mock(CreditTools.class);
    final ConnectorMcpHandler handler = new ConnectorMcpHandler(tools, mapper);

    JsonNode call(String json) {
        return mapper.readTree(handler.handle(json, USER).orElseThrow());
    }

    JsonNode callTool(String name, String arguments) {
        return call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\""
                + (arguments == null ? "" : ",\"arguments\":" + arguments) + "}}");
    }

    @Test
    void initializeNegotiatesTheVersionAndAdvertisesToolsOnly() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}")
                .path("result");
        assertThat(result.path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("capabilities").has("resources")).isFalse();
        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("creditagent");
        assertThat(result.path("instructions").asString()).contains("No tool takes an SSN");

        JsonNode unknown = call("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertThat(unknown.path("result").path("protocolVersion").asString()).isEqualTo(ConnectorMcpHandler.DEFAULT_PROTOCOL_VERSION);
        assertThat(unknown.path("id").asString()).isEqualTo("a");
    }

    @Test
    void listsFiveToolsNoneOfWhichTakesAnSsn() {
        JsonNode list = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").path("result").path("tools");
        assertThat(list).hasSize(5);
        assertThat(list.findValuesAsString("name")).containsExactly(
                "get_credit_advice", "get_credit_report", "get_credit_score", "get_report_lock_status", "set_report_lock");
        assertThat(list.toString()).doesNotContainIgnoringCase("\"ssn\"");
        for (JsonNode tool : list) {
            boolean write = tool.path("name").asString().equals("set_report_lock");
            assertThat(tool.path("annotations").path("readOnlyHint").booleanValue()).isEqualTo(!write);
            assertThat(tool.path("annotations").path("destructiveHint").booleanValue()).isFalse();
            assertThat(tool.path("inputSchema").path("type").asString()).isEqualTo("object");
        }
    }

    @Test
    void callsEachToolForTheConnectedUser() {
        given(tools.score(USER)).willReturn(new CreditTools.Score("***-**-6789", 712, 850, ScoreBand.GREEN));
        given(tools.lockStatus(USER)).willReturn(CreditTools.LockStatus.of("UNLOCKED"));
        given(tools.setLock(USER, true)).willReturn(CreditTools.LockStatus.of("LOCKED"));
        given(tools.advice(USER)).willReturn(new CreditAdvice("***-**-6789", 712, ScoreBand.GREEN, "Good",
                List.of(new Recommendation(1, "Pay down cards", "Below 30%", Recommendation.Impact.HIGH)), "rules"));
        given(tools.report(USER)).willReturn(new com.creditagent.common.CreditReport(
                "r-1", "***-**-6789", "Avery Doe", java.time.Instant.EPOCH, List.of(), 1, 0, 0));

        JsonNode score = callTool("get_credit_score", "{}").path("result");
        assertThat(score.path("isError").booleanValue()).isFalse();
        assertThat(score.path("structuredContent").path("score").intValue()).isEqualTo(712);
        assertThat(mapper.readTree(score.path("content").get(0).path("text").asString()).path("band").asString()).isEqualTo("GREEN");

        assertThat(callTool("get_credit_report", null).path("result").path("structuredContent").path("reportId").asString())
                .isEqualTo("r-1");
        assertThat(callTool("get_credit_advice", "null").path("result").path("structuredContent").path("source").asString())
                .isEqualTo("rules");
        assertThat(callTool("get_report_lock_status", "{}").path("result").path("structuredContent").path("locked").booleanValue())
                .isFalse();
        assertThat(callTool("set_report_lock", "{\"locked\":true}").path("result").path("structuredContent").path("reportStatus")
                .asString()).isEqualTo("LOCKED");
        verify(tools).setLock(USER, true);
    }

    @Test
    void toolFailuresComeBackAsErrorResults() {
        given(tools.score(any())).willThrow(new CreditTools.ToolException(CreditTools.UNAVAILABLE));
        JsonNode failed = callTool("get_credit_score", "{}").path("result");
        assertThat(failed.path("isError").booleanValue()).isTrue();
        assertThat(failed.path("content").get(0).path("text").asString()).isEqualTo(CreditTools.UNAVAILABLE);

        JsonNode badLock = callTool("set_report_lock", "{\"locked\":\"yes\"}").path("result");
        assertThat(badLock.path("isError").booleanValue()).isTrue();
        assertThat(badLock.path("content").get(0).path("text").asString()).contains("true or false");
        assertThat(callTool("set_report_lock", "{}").path("result").path("isError").booleanValue()).isTrue();
    }

    @Test
    void protocolMistakesAreJsonRpcErrors() {
        assertThat(callTool("get_everyones_reports", "{}").path("error").path("code").intValue())
                .isEqualTo(ConnectorMcpHandler.INVALID_PARAMS);
        assertThat(callTool("get_credit_score", "[1]").path("error").path("code").intValue())
                .isEqualTo(ConnectorMcpHandler.INVALID_PARAMS);
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}").path("error").path("code").intValue())
                .isEqualTo(ConnectorMcpHandler.METHOD_NOT_FOUND);
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":3}").path("error").path("code").intValue())
                .isEqualTo(ConnectorMcpHandler.INVALID_REQUEST);
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"ping\"}").path("error").path("code").intValue())
                .isEqualTo(ConnectorMcpHandler.INVALID_REQUEST);
        assertThat(call("not json").path("error").path("code").intValue()).isEqualTo(ConnectorMcpHandler.PARSE_ERROR);
        assertThat(call("[1,2]").path("error").path("code").intValue()).isEqualTo(ConnectorMcpHandler.INVALID_REQUEST);
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}").path("result").isObject()).isTrue();
    }

    @Test
    void notificationsGetNoAnswer() {
        assertThat(handler.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", USER)).isEmpty();
        assertThat(handler.handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"notifications/cancelled\"}", USER)).isEmpty();
        verifyNoInteractions(tools);
    }

    @Test
    void anUnexpectedFailureIsAnInternalErrorWithoutDetail() {
        given(tools.report(any())).willThrow(new IllegalStateException("secret detail 123-45-6789"));
        JsonNode answer = callTool("get_credit_report", "{}");
        assertThat(answer.path("error").path("code").intValue()).isEqualTo(ConnectorMcpHandler.INTERNAL_ERROR);
        assertThat(answer.toString()).doesNotContain("secret").doesNotContain("6789");
    }
}
