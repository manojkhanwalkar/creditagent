package com.creditagent.agent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.creditagent.agent.TestReports;
import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ScoreService;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditScore;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.service.V;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the agent and calls it with LangChain4j's agentic A2A client, the same client the Client
 * Agent uses. MCP and the score calculator are mocked; everything A2A is real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class A2aContractTest {

    public interface Advisor {
        @Agent(description = "credit advice", outputKey = "advice")
        String advise(@V("ssn") String ssn);
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    CreditReportSource reports;

    @MockitoBean
    ScoreService scores;

    /** Unlocked unless a test says otherwise (Mockito's default answer is false). */
    @MockitoBean
    com.creditagent.clients.ReportLockCheck locks;

    Advisor advisor;

    @BeforeEach
    void client() {
        advisor = AgenticServices.a2aBuilder("http://localhost:" + port, Advisor.class).build();
    }

    @Test
    void agentCardIsPublishedWithItsRealAddress() {
        JsonNode card = json.readTree(RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/.well-known/agent-card.json")
                .retrieve()
                .body(String.class));
        assertThat(card.get("name").asString()).isEqualTo(AgentCards.NAME);
        assertThat(card.toString()).contains("http://localhost:" + port);
    }

    @Test
    void returnsAdviceForAKnownSsn() {
        given(reports.findReport(Ssn.parse("123-45-6789"))).willReturn(Optional.of(TestReports.troubled()));
        given(scores.score(any())).willReturn(CreditScore.of(540));

        CreditAdvice advice = json.readValue(advisor.advise("123-45-6789"), CreditAdvice.class);

        assertThat(advice.score()).isEqualTo(540);
        assertThat(advice.band()).isEqualTo(ScoreBand.RED);
        assertThat(advice.ssnMasked()).isEqualTo("***-**-6789");
        assertThat(advice.recommendations()).isNotEmpty();
    }

    @Test
    void unknownSsnGetsAnErrorReply() {
        given(reports.findReport(any())).willReturn(Optional.empty());
        JsonNode reply = json.readTree(advisor.advise("234-56-7890"));
        assertThat(reply.get("error").asString()).contains("No credit report");
    }

    @Test
    void textWithoutAnSsnGetsAnErrorReply() {
        JsonNode reply = json.readTree(advisor.advise("hello"));
        assertThat(reply.has("error")).isTrue();
    }

    @Test
    void anUnexpectedFailureStillGetsAnErrorReply() {
        given(reports.findReport(any())).willReturn(Optional.of(TestReports.troubled()));
        given(scores.score(any())).willThrow(new IllegalStateException("malformed score"));
        JsonNode reply = json.readTree(advisor.advise("123-45-6789"));
        assertThat(reply.get("error").asString()).contains("analysis failed");
    }

    @Test
    void anOversizedRequestIsRefusedBeforeItIsRead() {
        String huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{\"pad\":\""
                + "x".repeat((int) A2aServerConfiguration.MAX_REQUEST_BYTES) + "\"}}";
        int status = RestClient.create().post()
                .uri("http://localhost:" + port + "/")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(huge)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(413);
    }

    @Test
    void aLockedReportIsRefusedBeforeItIsRead() {
        given(locks.isLocked(Ssn.parse("123-45-6789"))).willReturn(true);
        JsonNode reply = json.readTree(advisor.advise("123-45-6789"));
        assertThat(reply.get("error").asString()).isEqualTo("This credit report is locked by its owner.");
        org.mockito.Mockito.verifyNoInteractions(reports, scores);
    }

    @Test
    void whenTheLockCannotBeCheckedTheReportIsNotReleased() {
        given(locks.isLocked(any())).willThrow(new com.creditagent.clients.ServiceUnavailableException("down", null));
        JsonNode reply = json.readTree(advisor.advise("123-45-6789"));
        assertThat(reply.get("error").asString()).contains("temporarily unavailable");
        org.mockito.Mockito.verifyNoInteractions(reports, scores);
    }
}
