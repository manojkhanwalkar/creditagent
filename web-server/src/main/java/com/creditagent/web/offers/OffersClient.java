package com.creditagent.web.offers;

import com.creditagent.clients.ServiceUnavailableException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * The Offers service's REST API ({@code POST /api/v1/offers} with {@code {score, zip}}). It sends
 * nothing that identifies the user. A pure computation, so any transient failure may be retried.
 */
public class OffersClient {

    static final String PATH = "/api/v1/offers";

    private final RestClient restClient;

    public OffersClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    /** @throws ServiceUnavailableException if the service cannot answer */
    public JsonNode offers(int score, String zip) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("score", score);
        if (zip != null) {
            body.put("zip", zip);
        }
        try {
            JsonNode answer = restClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (answer == null || !answer.path("offers").isArray()) {
                throw new ServiceUnavailableException("Offers service returned no offers list", null);
            }
            return answer;
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ServiceUnavailableException("Offers service request failed", e);
        }
    }
}
