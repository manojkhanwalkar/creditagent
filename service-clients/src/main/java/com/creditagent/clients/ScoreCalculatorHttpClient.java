package com.creditagent.clients;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the standalone Score Calculator service. Construct with a {@link RestClient} whose base URL
 * points at the service (Spring Boot's auto-configured {@code RestClient.Builder} supplies the JSON
 * converters and observability).
 */
public class ScoreCalculatorHttpClient implements ScoreService {

    static final String PATH = "/api/v1/scores";

    private final RestClient restClient;

    public ScoreCalculatorHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    @Override
    public CreditScore score(CreditReport report) {
        try {
            CreditScore score = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(report)
                    .retrieve()
                    .body(CreditScore.class);
            if (score == null) {
                throw new ServiceUnavailableException("Score Calculator returned no body", null);
            }
            return score;
        } catch (RestClientException | HttpMessageConversionException | IllegalArgumentException e) {
            throw new ServiceUnavailableException("Score Calculator request failed", e);
        }
    }
}
