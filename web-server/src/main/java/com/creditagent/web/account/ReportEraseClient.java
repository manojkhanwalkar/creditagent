package com.creditagent.web.account;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.Ssn;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The report generator's internal erase API ({@code POST /internal/v1/reports/erase} with
 * {@code {ssn}}), with the service token. The SSN goes in the body, never in a URL. Idempotent: the
 * client retries transient failures.
 */
public class ReportEraseClient {

    static final String PATH = "/internal/v1/reports/erase";

    private final RestClient restClient;
    private final String serviceToken;

    public ReportEraseClient(RestClient restClient, String serviceToken) {
        this.restClient = Objects.requireNonNull(restClient);
        this.serviceToken = serviceToken;
    }

    /** @throws ServiceUnavailableException if the generator could not confirm the erase */
    public void erase(Ssn ssn) {
        try {
            restClient.post().uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ssn", ssn.digits()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("Report erase failed", e);
        }
    }
}
