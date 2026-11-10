package com.creditagent.web.alerts;

import com.creditagent.clients.ServiceUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The Alerts service's internal API ({@code POST /internal/v1/alerts/lookup} with {@code {email}}),
 * with the service token. The email goes in the body, never in a URL. A read: safe to retry.
 */
public class AlertsClient {

    static final String PATH = "/internal/v1/alerts/lookup";

    public record Finding(String location, Instant foundAt) {}

    public record Lookup(Boolean exposed, List<Finding> findings) {}

    private final RestClient restClient;
    private final String serviceToken;

    public AlertsClient(RestClient restClient, String serviceToken) {
        this.restClient = Objects.requireNonNull(restClient);
        this.serviceToken = serviceToken;
    }

    /** @throws ServiceUnavailableException if the service cannot answer */
    public Lookup lookup(String email) {
        try {
            Lookup answer = restClient.post().uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email))
                    .retrieve()
                    .body(Lookup.class);
            if (answer == null || answer.exposed() == null || answer.findings() == null) {
                throw new ServiceUnavailableException("Alerts service gave no answer", null);
            }
            return answer;
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ServiceUnavailableException("Alerts service request failed", e);
        }
    }
}
