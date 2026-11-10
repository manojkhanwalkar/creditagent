package com.creditagent.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.clients.ReportLockCheck;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.Ssn;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The web server and client agent clients against a mock server: requests sent, answers read. */
class DownstreamClientsTest {

    static final String SERVICE_TOKEN = "service-token-service-token-service-token";
    static final Ssn SSN = Ssn.parse("123-45-6789");
    static final ConnectorUser USER = new ConnectorUser("avery", SSN, "user-token", "https://c/mcp", "credit");

    RestClient.Builder builder = RestClient.builder().baseUrl("http://web");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    AccountClient accounts(String token) {
        return new AccountClient(builder.build(), token);
    }

    @Test
    void introspectionSendsTheTokenInTheBodyWithTheServiceToken() {
        server.expect(requestTo("http://web" + AccountClient.INTROSPECT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(jsonPath("$.token").value("user-token"))
                .andRespond(withSuccess("{\"active\":true,\"userId\":\"avery\",\"ssn\":\"123456789\","
                        + "\"resource\":\"https://c/mcp\",\"scope\":\"credit\"}", MediaType.APPLICATION_JSON));
        ConnectorUser user = accounts(SERVICE_TOKEN).authenticate("user-token").orElseThrow();
        assertThat(user.userId()).isEqualTo("avery");
        assertThat(user.ssn()).isEqualTo(SSN);
        assertThat(user.resource()).isEqualTo("https://c/mcp");
        assertThat(user.accessToken()).isEqualTo("user-token");
    }

    @Test
    void anInactiveTokenHasNoUser() {
        server.expect(requestTo("http://web" + AccountClient.INTROSPECT))
                .andRespond(withSuccess("{\"active\":false}", MediaType.APPLICATION_JSON));
        assertThat(accounts(SERVICE_TOKEN).authenticate("t")).isEmpty();
    }

    @Test
    void unusableIntrospectionAnswersAreOutages() {
        server.expect(requestTo("http://web" + AccountClient.INTROSPECT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).authenticate("t")).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://web" + AccountClient.INTROSPECT))
                .andRespond(withSuccess("{\"active\":true,\"userId\":\"a\",\"ssn\":\"nope\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).authenticate("t")).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://web" + AccountClient.INTROSPECT)).andRespond(withServerError());
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).authenticate("t")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void withoutAServiceTokenNothingIsSent() {
        assertThatThrownBy(() -> accounts(null).authenticate("t")).isInstanceOf(ServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void readsAndSetsTheReportStatus() {
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.token").value("user-token"))
                .andRespond(withSuccess("{\"reportStatus\":\"UNLOCKED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.reportStatus").value("LOCKED"))
                .andRespond(withSuccess("{\"reportStatus\":\"LOCKED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS))
                .andExpect(jsonPath("$.reportStatus").value("UNLOCKED"))
                .andRespond(withSuccess("{\"reportStatus\":\"UNLOCKED\"}", MediaType.APPLICATION_JSON));
        AccountClient client = accounts(SERVICE_TOKEN);
        assertThat(client.reportStatus(USER)).isEqualTo("UNLOCKED");
        assertThat(client.setReportStatus(USER, true)).isEqualTo("LOCKED");
        assertThat(client.setReportStatus(USER, false)).isEqualTo("UNLOCKED");
    }

    @Test
    void aRefusedUserTokenIsToldApartFromARefusedServiceToken() {
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).reportStatus(USER)).isInstanceOf(AccountClient.TokenRejectedException.class);
        server.reset();
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).reportStatus(USER)).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).reportStatus(USER)).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://web" + AccountClient.REPORT_STATUS))
                .andRespond(withSuccess("{\"reportStatus\":\"MAYBE\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> accounts(SERVICE_TOKEN).reportStatus(USER)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void adviceIsReadOrItsRefusalReasonPassedOn() {
        AdviceClient advice = new AdviceClient(builder.build());
        server.expect(requestTo("http://web" + AdviceClient.PATH))
                .andExpect(jsonPath("$.ssn").value("123456789"))
                .andRespond(withSuccess("{\"ssnMasked\":\"***-**-6789\",\"score\":700,\"band\":\"ORANGE\",\"summary\":\"s\","
                        + "\"recommendations\":[],\"source\":\"rules\"}", MediaType.APPLICATION_JSON));
        assertThat(advice.adviceFor(SSN).score()).isEqualTo(700);

        server.reset();
        server.expect(requestTo("http://web" + AdviceClient.PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":422,\"detail\":\"" + ReportLockCheck.LOCKED_MESSAGE + "\"}"));
        assertThatThrownBy(() -> advice.adviceFor(SSN)).isInstanceOf(AdviceClient.AdviceRefusedException.class)
                .hasMessage(ReportLockCheck.LOCKED_MESSAGE);

        server.reset();
        server.expect(requestTo("http://web" + AdviceClient.PATH)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> advice.adviceFor(SSN)).isInstanceOf(ServiceUnavailableException.class);

        server.reset();
        server.expect(requestTo("http://web" + AdviceClient.PATH)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> advice.adviceFor(SSN)).isInstanceOf(ServiceUnavailableException.class);

        server.reset();
        server.expect(requestTo("http://web" + AdviceClient.PATH)).andRespond(withSuccess());
        assertThatThrownBy(() -> advice.adviceFor(SSN)).isInstanceOf(ServiceUnavailableException.class);
    }
}
