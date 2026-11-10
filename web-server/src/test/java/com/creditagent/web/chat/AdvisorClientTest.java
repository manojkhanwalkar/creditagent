package com.creditagent.web.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditReport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AdvisorClientTest {

    static final String TOKEN = "service-token-service-token-service-token";
    static final String ID = "conversation-0123456789";
    static final CreditReport REPORT = new CreditReport("r-1", "***-**-6789", "Avery Doe", Instant.EPOCH, List.of(), 0, 0, 0);

    final RestClient.Builder builder = RestClient.builder().baseUrl("http://advisor");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestClient client = builder.build();

    @Test
    void sendsTheQuestionAndReportWithTheServiceToken() {
        server.expect(requestTo("http://advisor/internal/v1/conversations/" + ID + "/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.message").value("utilization?"))
                .andExpect(jsonPath("$.report.reportId").value("r-1"))
                .andRespond(withSuccess("{\"reply\":\"r\",\"disclaimer\":\"d\",\"refused\":false}", MediaType.APPLICATION_JSON));
        assertThat(new AdvisorClient(client, client, TOKEN).chat(ID, "utilization?", REPORT))
                .isEqualTo(new AdvisorClient.ChatReply("r", "d", false));
    }

    @Test
    void forgetsAConversation() {
        server.expect(requestTo("http://advisor/internal/v1/conversations/" + ID))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withNoContent());
        new AdvisorClient(client, client, TOKEN).forget(ID);
        server.verify();
    }

    @Test
    void failuresAndUnusableAnswersAreOutages() {
        server.expect(requestTo("http://advisor/internal/v1/conversations/" + ID + "/messages")).andRespond(withServerError());
        assertThatThrownBy(() -> new AdvisorClient(client, client, TOKEN).chat(ID, "q", REPORT))
                .isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://advisor/internal/v1/conversations/" + ID + "/messages"))
                .andRespond(withSuccess("{\"reply\":\"r\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new AdvisorClient(client, client, TOKEN).chat(ID, "q", REPORT))
                .isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://advisor/internal/v1/conversations/" + ID)).andRespond(withServerError());
        assertThatThrownBy(() -> new AdvisorClient(client, client, TOKEN).forget(ID))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void withoutAServiceTokenNothingIsSent() {
        assertThatThrownBy(() -> new AdvisorClient(client, client, null).chat(ID, "q", REPORT))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> new AdvisorClient(client, client, " ").forget(ID))
                .isInstanceOf(ServiceUnavailableException.class);
        server.verify();
    }
}
