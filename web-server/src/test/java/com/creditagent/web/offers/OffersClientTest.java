package com.creditagent.web.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.clients.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OffersClientTest {

    final RestClient.Builder builder = RestClient.builder().baseUrl("http://offers");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final OffersClient client = new OffersClient(builder.build());

    @Test
    void sendsOnlyTheScoreAndZip() {
        server.expect(requestTo("http://offers/api/v1/offers"))
                .andExpect(content().json("{\"score\":720,\"zip\":\"98101\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"offers\":[{\"offerId\":\"a\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.offers(720, "98101").path("offers").size()).isEqualTo(1);
        server.reset();
        server.expect(requestTo("http://offers/api/v1/offers"))
                .andExpect(content().json("{\"score\":600}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"offers\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.offers(600, null).path("offers").isArray()).isTrue();
    }

    @Test
    void failuresAndAnswersWithoutAListAreOutages() {
        server.expect(requestTo("http://offers/api/v1/offers")).andRespond(withServerError());
        assertThatThrownBy(() -> client.offers(700, null)).isInstanceOf(ServiceUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://offers/api/v1/offers")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.offers(700, null)).isInstanceOf(ServiceUnavailableException.class);
    }
}
