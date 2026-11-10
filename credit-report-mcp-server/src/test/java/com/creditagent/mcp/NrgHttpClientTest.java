package com.creditagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.common.bureau.Bureau;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NrgHttpClientTest {

    MockRestServiceServer server;
    NrgHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://nrg");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NrgHttpClient(builder.build());
    }

    @Test
    void postsEachBureauUnderItsOwnKey() {
        server.expect(requestTo("http://nrg/api/v1/normalized-reports"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"equifax\":{\"a\":1},\"transunion\":{\"b\":2}}", true))
                .andRespond(withSuccess("{\"reportId\":\"n-1\"}", MediaType.APPLICATION_JSON));

        Map<Bureau, String> reports = new EnumMap<>(Bureau.class);
        reports.put(Bureau.EQUIFAX, "{\"a\":1}");
        reports.put(Bureau.TRANSUNION, "{\"b\":2}");

        assertThat(client.normalize(reports)).isEqualTo("{\"reportId\":\"n-1\"}");
        server.verify();
    }

    @Test
    void malformedFileIsRejectedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> client.normalize(Map.of(Bureau.EXPERIAN, "{\"x\":1}, \"injected\": {")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Experian credit report is not valid JSON");
        server.verify();
    }

    @Test
    void nrgFailureSurfacesAsIllegalState() {
        server.expect(requestTo("http://nrg/api/v1/normalized-reports")).andRespond(withServerError());
        assertThatThrownBy(() -> client.normalize(Map.of(Bureau.EQUIFAX, "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NRG request failed");
    }
}
