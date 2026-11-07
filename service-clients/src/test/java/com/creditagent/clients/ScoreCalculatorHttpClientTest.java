package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import com.creditagent.common.ScoreBand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ScoreCalculatorHttpClientTest {

    static final CreditReport REPORT = new CreditReport("r-1", "***-**-6789", "A B", Instant.EPOCH, List.of(), 1, 0, 0);

    MockRestServiceServer server;
    ScoreCalculatorHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://score");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ScoreCalculatorHttpClient(builder.build());
    }

    @Test
    void postsTheReportAndReadsTheScore() {
        server.expect(requestTo("http://score/api/v1/scores"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reportId").value("r-1"))
                .andRespond(withSuccess("{\"value\":640,\"band\":\"ORANGE\",\"max\":850}", MediaType.APPLICATION_JSON));

        CreditScore score = client.score(REPORT);

        assertThat(score.value()).isEqualTo(640);
        assertThat(score.band()).isEqualTo(ScoreBand.ORANGE);
        server.verify();
    }

    @Test
    void serverErrorsBecomeServiceUnavailable() {
        server.expect(requestTo("http://score/api/v1/scores")).andRespond(withServerError());
        assertThatThrownBy(() -> client.score(REPORT)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void inconsistentScoresAreRejected() {
        server.expect(requestTo("http://score/api/v1/scores"))
                .andRespond(withSuccess("{\"value\":900,\"band\":\"GREEN\",\"max\":850}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.score(REPORT)).isInstanceOf(ServiceUnavailableException.class);
    }
}
