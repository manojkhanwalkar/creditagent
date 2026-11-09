package com.creditagent.connector;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The web server's internal connector API ({@code /internal/v1/connector/*}), with the service token.
 * Each call sends the user's access token in the body; the web server decides whose account it is.
 *
 * <p>Every call is safe to repeat (introspection and status reads are reads; setting the lock to a
 * value twice leaves the same value), so transient failures are retried ({@link HttpClients}).
 */
public class AccountClient {

    static final String INTROSPECT = "/internal/v1/connector/introspect";
    static final String REPORT_STATUS = "/internal/v1/connector/report-status";

    /** The web server no longer accepts the access token (revoked or expired since the request began). */
    public static class TokenRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TokenRejectedException() {
            super("The access token is no longer active", null, false, false);
        }
    }

    record Introspection(Boolean active, String userId, String ssn, String resource, String scope) {
        @Override
        public String toString() {
            return "Introspection[active=" + active + ", userId=" + userId + "]";
        }
    }

    record StatusBody(String reportStatus) {}

    private final RestClient restClient;
    private final String serviceToken;

    public AccountClient(RestClient restClient, String serviceToken) {
        this.restClient = Objects.requireNonNull(restClient);
        this.serviceToken = serviceToken;
    }

    public static AccountClient create(RestClient.Builder builder, ConnectorProperties properties, RetrySettings retry) {
        return new AccountClient(
                HttpClients.restClient(builder, properties.webServerUrl(), properties.timeout(), retry),
                properties.serviceToken());
    }

    /**
     * @return the token's user, or empty if the token is not live
     * @throws ServiceUnavailableException if the web server cannot answer
     */
    public Optional<ConnectorUser> authenticate(String accessToken) {
        Introspection answer = call(() -> restClient.post().uri(INTROSPECT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", accessToken))
                .retrieve()
                .body(Introspection.class));
        if (answer == null || answer.active() == null) {
            throw new ServiceUnavailableException("Token introspection returned no answer", null);
        }
        if (!answer.active() || answer.userId() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ConnectorUser(
                    answer.userId(), Ssn.parse(answer.ssn()), accessToken, answer.resource(), answer.scope()));
        } catch (InvalidSsnException e) {
            throw new ServiceUnavailableException("Token introspection returned an unusable answer", null);
        }
    }

    /** {@code LOCKED} or {@code UNLOCKED}. @throws TokenRejectedException if the token stopped working */
    public String reportStatus(ConnectorUser user) {
        return status(call(() -> restClient.post().uri(REPORT_STATUS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", user.accessToken()))
                .retrieve()
                .body(StatusBody.class)));
    }

    /** Locks or unlocks the user's report. @return the new status */
    public String setReportStatus(ConnectorUser user, boolean locked) {
        return status(call(() -> restClient.put().uri(REPORT_STATUS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", user.accessToken(), "reportStatus", locked ? "LOCKED" : "UNLOCKED"))
                .retrieve()
                .body(StatusBody.class)));
    }

    private static String status(StatusBody body) {
        if (body == null || !("LOCKED".equals(body.reportStatus()) || "UNLOCKED".equals(body.reportStatus()))) {
            throw new ServiceUnavailableException("Report status answer was unusable", null);
        }
        return body.reportStatus();
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        if (serviceToken == null) {
            throw new ServiceUnavailableException("The connector has no INTERNAL_API_TOKEN", null);
        }
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            // The user's token was refused (invalid_token). A refused service token is a 401 without
            // that error, and is a configuration fault, not the user's.
            HttpHeaders headers = e.getResponseHeaders();
            String challenge = headers == null ? null : headers.getFirst(HttpHeaders.WWW_AUTHENTICATE);
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED) && challenge != null
                    && challenge.contains("invalid_token")) {
                throw new TokenRejectedException();
            }
            throw new ServiceUnavailableException("Web server refused the connector call", e);
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ServiceUnavailableException("Web server request failed", e);
        }
    }
}
