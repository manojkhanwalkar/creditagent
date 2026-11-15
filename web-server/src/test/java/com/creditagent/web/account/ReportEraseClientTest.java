package com.creditagent.web.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.Ssn;
import com.creditagent.web.alerts.AlertsClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ReportEraseClientTest {

    static final String TOKEN = "service-token-service-token-service-token";

    @Test
    void sendsTheSsnInTheBodyWithTheTokenAndMapsFailures() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://generator");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReportEraseClient client = new ReportEraseClient(builder.build(), TOKEN);
        server.expect(requestTo("http://generator/internal/v1/reports/erase")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("{\"ssn\":\"123456789\"}", JsonCompareMode.STRICT))
                .andRespond(withNoContent());
        server.expect(requestTo("http://generator/internal/v1/reports/erase")).andRespond(withServerError());
        client.erase(Ssn.parse("123-45-6789"));
        assertThatThrownBy(() -> client.erase(Ssn.parse("123-45-6789"))).isInstanceOf(ServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void alertsEraseSendsTheEmailInTheBodyAndMapsFailures() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://alerts");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AlertsClient client = new AlertsClient(builder.build(), TOKEN);
        server.expect(requestTo("http://alerts/internal/v1/alerts/erase")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("{\"email\":\"ada@example.com\"}", JsonCompareMode.STRICT))
                .andRespond(withNoContent());
        server.expect(requestTo("http://alerts/internal/v1/alerts/erase")).andRespond(withServerError());
        client.erase("ada@example.com");
        assertThatThrownBy(() -> client.erase("ada@example.com")).isInstanceOf(ServiceUnavailableException.class);
        server.verify();
    }
}
