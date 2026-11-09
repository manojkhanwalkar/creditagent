package com.creditagent.clients;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where third-party consumers check report locks ({@code credit.report-lock.*}).
 *
 * @param url the web server, which owns the users table, e.g. {@code http://localhost:8080}
 * @param token the shared internal API token ({@code INTERNAL_API_TOKEN}); without it every check
 *     fails, so third-party pulls are refused
 * @param timeout connect/read timeout per try; defaults to 5 s
 */
@ConfigurationProperties("credit.report-lock")
public record ReportLockProperties(String url, String token, Duration timeout) {

    public ReportLockProperties {
        url = url == null || url.isBlank() ? "http://localhost:8080" : url;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return "ReportLockProperties[url=" + url + ", token=<redacted>, timeout=" + timeout + "]";
    }
}
