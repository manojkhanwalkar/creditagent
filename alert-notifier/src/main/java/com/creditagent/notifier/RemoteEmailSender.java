package com.creditagent.notifier;

import com.creditagent.alerts.Alert;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends the dark-web alert through the notification-service ({@code POST /internal/v1/notifications},
 * template {@code DARK_WEB_ALERT}, contract {@code notification-service-v1.yaml}). The service owns the
 * wording; this sends only the count, the first-seen date and the sign-in address.
 *
 * <p>The {@code Idempotency-Key} is {@code alert:<HMAC of the address>:<run time>}: never the address
 * itself, and the same for one user within one run. Each request is sent once: the service retries its
 * providers itself. Every failure throws, so {@link AlertNotifier} releases the claims and the next run
 * tries again: 422 is {@link Refused} (not delivered); 429, 5xx, a timeout or a connection failure is
 * {@link Unknown} (it may arrive, and a later duplicate is accepted over a lost alert); any other 4xx is
 * a caller bug ({@link IllegalStateException}). Any 202, {@code SENT} or {@code UNCERTAIN} (an earlier
 * send with the key had an unknown outcome and is not repeated), is success.
 */
public final class RemoteEmailSender implements EmailSender {

    static final String PATH = "/internal/v1/notifications";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** The service clearly refused the message: it was not delivered. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(int status) {
            super("notification-service refused the alert (" + status + ")");
        }
    }

    /** The outcome is unknown: the alert may still arrive. */
    public static final class Unknown extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unknown(String reason, Throwable cause) {
            super("notification-service outcome unknown (" + reason + ")", cause);
        }
    }

    private final RestClient client;
    private final String token;
    private final byte[] keyKey;

    public RemoteEmailSender(RestClient client, String token) {
        this.client = Objects.requireNonNull(client);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Alert emails need INTERNAL_API_TOKEN, the notification-service's token");
        }
        this.token = token;
        this.keyKey = hmac(token.getBytes(StandardCharsets.UTF_8), "credit-agent/alert-idempotency-key/v1");
    }

    /** A client for {@code url}: bounded by {@code timeout} (connect and read), no redirects. */
    public static RemoteEmailSender create(String url, Duration timeout, String token) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return new RemoteEmailSender(RestClient.builder().baseUrl(url).requestFactory(factory).build(), token);
    }

    @Override
    public void sendAlert(String to, List<Alert> alerts, Instant runAt, String appUrl) {
        Map<String, Object> body = body(to, alerts, appUrl);
        int status;
        try {
            status = client.post().uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(IDEMPOTENCY_KEY, idempotencyKey(to, runAt))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RestClientException e) {
            throw new Unknown(e.getClass().getSimpleName(), e);
        }
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 422) {
            throw new Refused(status);
        }
        if (status == 429 || status >= 500) {
            throw new Unknown(Integer.toString(status), null);
        }
        throw new IllegalStateException("notification-service rejected the request (" + status + ")");
    }

    /** {@code channel}, {@code to}, {@code template} and the params {@code count}, {@code firstSeen}, {@code appUrl}. */
    static Map<String, Object> body(String to, List<Alert> alerts, String appUrl) {
        LocalDate firstSeen = alerts.stream().map(a -> LocalDate.ofInstant(a.timestamp(), ZoneOffset.UTC))
                .min(LocalDate::compareTo).orElseThrow(() -> new IllegalArgumentException("no alerts"));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("count", Integer.toString(alerts.size()));
        params.put("firstSeen", firstSeen.toString());
        if (appUrl != null && !appUrl.isBlank()) {
            params.put("appUrl", appUrl);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "EMAIL");
        body.put("to", to);
        body.put("template", "DARK_WEB_ALERT");
        body.put("params", params);
        return body;
    }

    /** {@code alert:<HMAC-SHA256 of the address, base64url>:<run time in epoch ms>}; 64 characters. */
    String idempotencyKey(String to, Instant runAt) {
        String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(keyKey, to));
        return "alert:" + hash + ":" + runAt.toEpochMilli();
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
