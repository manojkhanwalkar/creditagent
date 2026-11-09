package com.creditagent.connector;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.Ssn;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks the client agent ({@code POST /api/v1/advice}) for advice; it reaches the Credit Intelligence
 * Agent over A2A. Advice can take a slow LLM call, so it is not retried after it may have arrived:
 * only failures that prove it never did, and 429 or 503 refusals.
 */
public class AdviceClient {

    static final String PATH = "/api/v1/advice";

    /** The agent answered, but refused (the report is locked, there is no report, …). */
    public static class AdviceRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AdviceRefusedException(String reason) {
            super(reason, null, false, false);
        }
    }

    private final RestClient restClient;

    public AdviceClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    public static AdviceClient create(RestClient.Builder builder, ConnectorProperties properties, RetrySettings retry) {
        return new AdviceClient(
                HttpClients.restClient(builder, properties.clientAgentUrl(), properties.adviceTimeout(), retry, false));
    }

    /**
     * @throws AdviceRefusedException if the agent refused, with its reason
     * @throws ServiceUnavailableException if it could not be reached
     */
    public CreditAdvice adviceFor(Ssn ssn) {
        try {
            CreditAdvice advice = restClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("ssn", ssn.digits()))
                    .retrieve()
                    .body(CreditAdvice.class);
            if (advice == null) {
                throw new ServiceUnavailableException("Client agent returned no advice", null);
            }
            return advice;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)) {
                ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
                throw new AdviceRefusedException(problem == null ? null : problem.getDetail());
            }
            throw new ServiceUnavailableException("Client agent refused the request", e);
        } catch (RestClientException | HttpMessageConversionException | IllegalStateException e) {
            throw new ServiceUnavailableException("Client agent request failed", e);
        }
    }
}
