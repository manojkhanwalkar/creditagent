package com.creditagent.clients;

import com.creditagent.common.Ssn;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Checks report locks with the web server's internal API: {@code POST /internal/v1/report-locks/lookup}
 * with {@code {"ssn":"…"}} (never in the URL) and the internal API token as a bearer token.
 *
 * <p>The check is a read, but a slow one can be two DynamoDB calls deep on the web server, so a
 * timeout is not retried (that would only repeat work still in progress). Only failures that prove
 * the request never arrived, and 429 or 503, are retried. Any failure that remains, a missing token
 * included, is a {@link ServiceUnavailableException}.
 */
public class ReportLockHttpClient implements ReportLockCheck {

    static final String PATH = "/internal/v1/report-locks/lookup";

    record LockAnswer(Boolean locked) {}

    private final RestClient restClient;
    private final String token;

    public ReportLockHttpClient(RestClient restClient, String token) {
        this.restClient = Objects.requireNonNull(restClient);
        this.token = token;
    }

    /** Built the way every service-to-service client is: timeouts, no redirects, retries. */
    public static ReportLockHttpClient create(
            RestClient.Builder builder, ReportLockProperties properties, RetrySettings retry) {
        return new ReportLockHttpClient(
                HttpClients.restClient(builder, properties.url(), properties.timeout(), retry, false), properties.token());
    }

    @Override
    public boolean isLocked(Ssn ssn) {
        if (token == null || token.isBlank()) {
            throw new ServiceUnavailableException("Report lock check is not configured (INTERNAL_API_TOKEN)", null);
        }
        try {
            LockAnswer answer = restClient.post()
                    .uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("ssn", ssn.digits()))
                    .retrieve()
                    .body(LockAnswer.class);
            if (answer == null || answer.locked() == null) {
                throw new ServiceUnavailableException("Report lock check returned no answer", null);
            }
            return answer.locked();
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ServiceUnavailableException("Report lock check failed", e);
        }
    }
}
