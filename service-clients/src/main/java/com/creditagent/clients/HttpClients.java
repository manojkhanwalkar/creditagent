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
        return restClient(builder, baseUrl, timeout, timeout, retry, idempotent);
    }

    /**
     * As {@link #restClient(RestClient.Builder, String, Duration, RetrySettings, boolean)}, with a
     * separate, usually shorter, connect timeout: a service that cannot be reached fails fast, while
     * a slow answer still gets {@code readTimeout}.
     */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
            Duration readTimeout, RetrySettings retry, boolean idempotent) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(connectTimeout));
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new RetryingInterceptor(retry, idempotent))
                .build();
    }

    /** No redirects, and {@code connectTimeout} to open a connection. */
    static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
