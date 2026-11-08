package com.creditagent.clients;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Small helpers so every service builds its outbound clients the same way: connect and read
 * timeouts, no redirects, and retries of recoverable failures ({@link RetryingInterceptor}).
 */
public final class HttpClients {

    private HttpClients() {}

    /**
     * A client for an idempotent service (reads and pure computations), retried on any transient
     * failure.
     */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration timeout, RetrySettings retry) {
        return restClient(builder, baseUrl, timeout, retry, true);
    }

    /**
     * @param idempotent false for a service whose calls must not run twice; then only failures that
     *     prove the request never arrived, and 429 or 503, are retried
     */
    public static RestClient restClient(
            RestClient.Builder builder, String baseUrl, Duration timeout, RetrySettings retry, boolean idempotent) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new RetryingInterceptor(retry, idempotent))
                .build();
    }
}
