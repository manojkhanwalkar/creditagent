package com.creditagent.web.notify;

import com.creditagent.clients.HttpClients;
import com.creditagent.clients.Retries;
import com.creditagent.clients.RetrySettings;
import com.creditagent.web.auth.otp.OtpDeliveryException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends through the notification-service ({@code POST /internal/v1/notifications}, contract
 * {@code notification-service-v1.yaml}), with the service token and the notification's idempotency key.
 *
 * <p>Retries only a connection that failed before the request was sent (refused, unknown host, connect
 * timeout), with the backoff and jitter of {@link RetrySettings}; the same key makes that safe. Never a
 * read timeout or a 5xx: the service retries its providers itself and must stay the one retry layer.
 *
 * <p>Answers: 2xx sent (a 202 {@code SENT}, or {@code UNCERTAIN} when an earlier send with this key had
 * an unknown outcome and is not re-sent: success here too); 422 refused (certainly not delivered); 429,
 * 5xx, a timeout or a connection that kept failing: outcome unknown (may have been delivered); any other
 * 4xx (400, 401, 404, 409, 413) is a bug in this caller or its configuration, so
 * {@link IllegalStateException}.
 */
public final class RemoteNotificationGateway implements NotificationGateway {

    static final String PATH = "/internal/v1/notifications";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient client;
    private final RetrySettings retry;
    private final String token;

    /** @param client sends each request once (no retrying interceptor): this class retries connect failures */
    public RemoteNotificationGateway(RestClient client, RetrySettings retry, String token) {
        this.client = Objects.requireNonNull(client);
        this.retry = Objects.requireNonNull(retry);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "NOTIFICATIONS_MODE=remote needs INTERNAL_API_TOKEN, the notification-service's token");
        }
        this.token = token;
    }

    /**
     * A client for {@code url} built the shared way and sent once per try: {@code connectTimeout} (short)
     * to connect, {@code timeout} for the answer. With the default 2 s, 10 s and 3 tries, the worst case
     * is about 14.5 s: two connects that time out, the backoff, then a try that runs to its timeout.
     */
    public static RemoteNotificationGateway create(RestClient.Builder builder, String url, Duration connectTimeout,
            Duration timeout, RetrySettings retry, String token) {
        return new RemoteNotificationGateway(
                HttpClients.restClient(builder, url, connectTimeout, timeout, RetrySettings.none(), false), retry, token);
    }

    @Override
    public void send(Notification n) {
        if (n.template() != Template.OTP_SIGN_IN) {
            throw new IllegalArgumentException("web-server does not send " + n.template());
        }
        Map<String, Object> body = body(n);
        int status;
        try {
            status = Retries.call(retry, RemoteNotificationGateway::connectFailed, () -> post(n.idempotencyKey(), body));
        } catch (RestClientException e) {
            // A read timeout, or a connection that kept failing: it may have been sent.
            throw new OtpDeliveryException(n.channel(), e, false);
        }
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 422) {
            throw new OtpDeliveryException(n.channel(),
                    new IllegalStateException("notification-service refused the message (422)"), true);
        }
        if (status == 429 || status >= 500) {
            throw new OtpDeliveryException(n.channel(),
                    new IllegalStateException("notification-service outcome unknown (" + status + ")"), false);
        }
        throw new IllegalStateException("notification-service rejected the request (" + status + ")");
    }

    /** The request body: {@code channel}, {@code to}, {@code template}, {@code params}. */
    static Map<String, Object> body(Notification n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", n.channel().name());
        body.put("to", n.to());
        body.put("template", n.template().name());
        body.put("params", n.params());
        return body;
    }

    private int post(String key, Map<String, Object> body) {
        return client.post().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    /** Only failures that prove the request never left; a read timeout is never retried. */
    static boolean connectFailed(Throwable t) {
        return Retries.notDelivered(t);
    }
}
