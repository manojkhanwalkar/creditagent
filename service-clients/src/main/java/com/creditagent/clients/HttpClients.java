package com.creditagent.clients;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Small helpers so every service builds its outbound clients the same way (with timeouts). */
public final class HttpClients {

    private HttpClients() {}

    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
