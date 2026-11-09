package com.creditagent.agent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ScoreService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every malformed or unsupported JSON-RPC request gets the matching JSON-RPC error, never a 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class A2aJsonRpcErrorsTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    CreditReportSource reports;

    @MockitoBean
    ScoreService scores;

    @MockitoBean
    ReportLockCheck locks;

    JsonNode post(String body) {
        String response = RestClient.create().post()
                .uri("http://localhost:" + port + "/")
                .header("A2A-Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return json.readTree(response);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "not JSON                | {bad json                                                                        | -32700",
        "not an object           | [1,2]                                                                            | -32600",
        "unknown method          | {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"NoSuchMethod\",\"params\":{}}         | -32601",
        "params not an object    | {\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"SendMessage\",\"params\":\"oops\"}    | -32602",
        "missing message         | {\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"SendMessage\",\"params\":{}}          | -32602",
        "object id               | {\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"GetTask\",\"params\":{\"id\":\"t\"}} | -32600",
        "cancel is unsupported   | {\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"CancelTask\",\"params\":{\"id\":\"t\"}} | -32004",
        "list is unsupported     | {\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ListTasks\",\"params\":{}}            | -32004"
    })
    void answersWithTheMatchingJsonRpcError(String name, String body, int code) {
        JsonNode response = post(body.strip());
        assertThat(response.path("error").path("code").asInt()).isEqualTo(code);
        assertThat(response.has("result")).isFalse();
    }

    @ParameterizedTest(name = "unknown task")
    @CsvSource("missing-task")
    void anUnknownTaskIsAnError(String taskId) {
        JsonNode response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"GetTask\",\"params\":{\"id\":\"" + taskId + "\"}}");
        assertThat(response.has("error")).isTrue();
        assertThat(response.path("id").asInt()).isEqualTo(2);
    }
}
