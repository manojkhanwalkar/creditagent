package com.creditagent.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.clients.RetrySettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** On a real server: MockMvc does not apply filter registrations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "credit.client.intelligence-agent-url=http://agent.invalid")
class RequestLimitsTest {

    @LocalServerPort
    int port;

    @Autowired
    ClientAgentProperties properties;

    @Test
    void anOversizedBodyIs413BeforeItIsRead() throws Exception {
        String huge = "{\"ssn\":\"" + "1".repeat((int) ClientAgentConfiguration.MAX_REQUEST_BYTES) + "\"}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/advice"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(huge))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void theClientWaitsLongerThanTheAgentsOwnWorstCase() {
        // Agent: one MCP look-up (MCP_TIMEOUT 20 s) + the Score Calculator with its retries
        // (DOWNSTREAM_TIMEOUT 5 s per try) + one LLM try (LLM_TIMEOUT 30 s). The MCP and LLM
        // defaults live in the agent module, so they are repeated here.
        Duration agentWorstCase = Duration.ofSeconds(20)
                .plus(RetrySettings.DEFAULTS.worstCase(Duration.ofSeconds(5)))
                .plusSeconds(30);
        assertThat(properties.timeout()).isGreaterThan(agentWorstCase);
    }
}
