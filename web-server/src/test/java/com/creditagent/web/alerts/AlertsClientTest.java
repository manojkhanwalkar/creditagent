package com.creditagent.web.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.web.auth.AuthService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AlertsClientTest {

    static final String TOKEN = "service-token-service-token-service-token";
    final RestClient.Builder builder = RestClient.builder().baseUrl("http://alerts");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final AlertsClient client = new AlertsClient(builder.build(), TOKEN);

    @Test
    void sendsTheEmailInTheBodyWithTheServiceToken() {
        server.expect(requestTo("http://alerts/internal/v1/alerts/lookup"))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("{\"email\":\"ada@example.com\"}", JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"exposed\":true,\"findings\":[{\"location\":\"l\",\"foundAt\":\"2026-11-09T12:00:00Z\"}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.lookup("ada@example.com").findings()).hasSize(1);
    }

    @Test
    void failuresAndShapelessAnswersAreOutages() {
        server.expect(requestTo("http://alerts/internal/v1/alerts/lookup")).andRespond(withServerError());
        assertThatThrownBy(() -> client.lookup("a@b.co")).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://alerts/internal/v1/alerts/lookup")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.lookup("a@b.co")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void atMostTwentyFindingsAreShown() {
        AuthService auth = mock(AuthService.class);
        AlertsClient client = mock(AlertsClient.class);
        com.creditagent.web.auth.User user = new com.creditagent.web.auth.User("ada", "$2a$h", "ada@example.com", "+14155550100",
                "h", "6789", "k", java.time.Instant.EPOCH, com.creditagent.web.auth.ReportStatus.UNLOCKED);
        org.mockito.BDDMockito.given(auth.account("ada")).willReturn(Optional.of(user));
        java.util.List<AlertsClient.Finding> many = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new AlertsClient.Finding("loc-" + i, java.time.Instant.EPOCH)).toList();
        org.mockito.BDDMockito.given(client.lookup("ada@example.com")).willReturn(new AlertsClient.Lookup(true, many));
        AlertsService.View view = new AlertsService(auth, client, client).current("ada");
        assertThat(view.findings()).hasSize(AlertsService.MAX_FINDINGS).first().extracting(AlertsClient.Finding::location).isEqualTo("loc-0");
    }

    @Test
    void anAccountWithoutAnEmailHasNoAlertsAndLoginUsesTheQuickClient() {
        AuthService auth = mock(AuthService.class);
        AlertsClient retrying = mock(AlertsClient.class);
        AlertsClient once = mock(AlertsClient.class);
        org.mockito.BDDMockito.given(auth.account("x")).willReturn(Optional.empty());
        AlertsService alerts = new AlertsService(auth, retrying, once);
        assertThat(alerts.alertsFor("x").exposed()).isFalse();
        assertThat(alerts.current("x").status()).isEqualTo(AlertsService.Status.OK);
        org.mockito.Mockito.verifyNoInteractions(retrying, once);
    }
}
