package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.common.CreditReport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientWiringTest {

    @Test
    void everyServiceGetsRetryAndReportLockSettingsBoundFromConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ServiceClientsAutoConfiguration.class))
                .withPropertyValues("credit.retry.max-attempts=5", "credit.report-lock.url=http://web:8080",
                        "credit.report-lock.token=" + "t".repeat(32))
                .run(context -> {
                    assertThat(context.getBean(RetrySettings.class).maxAttempts()).isEqualTo(5);
                    ReportLockProperties lock = context.getBean(ReportLockProperties.class);
                    assertThat(lock.url()).isEqualTo("http://web:8080");
                    assertThat(lock.hasToken()).isTrue();
                    assertThat(lock.timeout()).isEqualTo(ReportLockProperties.DEFAULT_TIMEOUT);
                });
    }

    @Test
    void reportLockDefaultsPointAtALocalWebServerWithoutAToken() {
        ReportLockProperties defaults = new ReportLockProperties(" ", null, null);
        assertThat(defaults.url()).isEqualTo("http://localhost:8080");
        assertThat(defaults.hasToken()).isFalse();
        assertThat(new ReportLockProperties(null, " ", Duration.ofSeconds(3)).hasToken()).isFalse();
        assertThat(ReportLockHttpClient.create(RestClient.builder(), defaults, RetrySettings.none())).isNotNull();
    }

    @Test
    void anEmptyScoreAnswerIsAServerProblem() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://score.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://score.test/api/v1/scores")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        ScoreCalculatorHttpClient client = new ScoreCalculatorHttpClient(builder.build());

        CreditReport report = new CreditReport("r", "***-**-6789", "A", Instant.now(), List.of(), 0, 0, 0);
        assertThatThrownBy(() -> client.score(report)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void anInterruptedPauseStopsRetryingAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThat(Retries.pause(Duration.ofSeconds(5))).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
