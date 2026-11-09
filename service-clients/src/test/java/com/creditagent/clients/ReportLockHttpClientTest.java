package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.common.Ssn;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ReportLockHttpClientTest {

    static final String URL = "http://web.test/internal/v1/report-locks/lookup";
    static final RetrySettings FAST = new RetrySettings(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(4), Duration.ZERO);

    MockRestServiceServer server;

    ReportLockHttpClient client(String token) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://web.test")
                .requestInterceptor(new RetryingInterceptor(FAST, true));
        server = MockRestServiceServer.bindTo(builder).build();
        return new ReportLockHttpClient(builder.build(), token);
    }

    @Test
    void sendsTheSsnInTheBodyWithTheTokenAndReadsTheAnswer() {
        ReportLockHttpClient client = client("t0ken");
        server.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer t0ken"))
                .andExpect(content().json("{\"ssn\":\"123456789\"}"))
                .andRespond(withSuccess("{\"locked\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"locked\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.isLocked(Ssn.parse("123-45-6789"))).isTrue();
        assertThat(client.isLocked(Ssn.parse("123-45-6789"))).isFalse();
        server.verify();
    }

    @Test
    void anyFailureMeansTheLockIsUnknownSoCallersMustRefuse() {
        ReportLockHttpClient client = client("t0ken");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.isLocked(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> client.isLocked(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> client.isLocked(Ssn.parse("123-45-6789")))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("no answer");
        server.verify();
    }

    @Test
    void withoutATokenNothingIsSentAndTheCheckFails() {
        ReportLockHttpClient client = client(" ");
        assertThatThrownBy(() -> client.isLocked(Ssn.parse("123-45-6789")))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("INTERNAL_API_TOKEN");
        server.verify();
    }

    @Test
    void theTokenIsNeverPrinted() {
        assertThat(new ReportLockProperties(null, "secret-token", null).toString())
                .doesNotContain("secret-token").contains("localhost:8080");
    }
}
