package com.creditagent.clients;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where third-party consumers check report locks ({@code credit.report-lock.*}).
 *
 * @param url the web server, which owns the users table, e.g. {@code http://localhost:8080}
 * @param token the shared internal API token ({@code INTERNAL_API_TOKEN}); without it every check
 *     fails, so third-party pulls are refused
 * @param timeout connect/read timeout per try; defaults to 12 s, longer than the web server's own
 *     worst case for one lookup (two DynamoDB reads of up to 5 s each)
 */
@ConfigurationProperties("credit.report-lock")
public record ReportLockProperties(String url, String token, Duration timeout) {

    public ReportLockProperties {
        url = url == null || url.isBlank() ? "http://localhost:8080" : url;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(12);

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return "ReportLockProperties[url=" + url + ", token=<redacted>, timeout=" + timeout + "]";
    }
}
