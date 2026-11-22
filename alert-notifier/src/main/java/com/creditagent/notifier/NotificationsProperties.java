package com.creditagent.notifier;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How alert emails are sent ({@code credit.notifications}).
 *
 * @param mode {@code local} (the SMTP relay, the default) or {@code remote} (the notification-service)
 * @param url the notification-service base URL: absolute http(s) with a host, and https unless the host
 *     is local ({@code localhost}, {@code 127.0.0.1}, {@code ::1}) or a compose service name (no dot), so
 *     addresses and the service token never cross a network in clear text
 * @param timeout longest one call to the service may take; above the service's own worst case (about 8 s)
 */
@ConfigurationProperties("credit.notifications")
public record NotificationsProperties(Mode mode, String url, Duration timeout) {

    public enum Mode { LOCAL, REMOTE }

    /** Hosts where plain http is acceptable: this machine. A name without a dot is a compose service. */
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public NotificationsProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        url = url == null || url.isBlank() ? "http://localhost:8094" : url.strip();
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("credit.notifications.timeout must be positive");
        }
        requireSafeUrl(url);
    }

    static void requireSafeUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("NOTIFICATIONS_URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || host.isEmpty() || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("NOTIFICATIONS_URL must be an absolute http or https URL with a host");
        }
        if (scheme.equals("http") && !LOCAL_HOSTS.contains(host) && host.contains(".")) {
            throw new IllegalArgumentException("NOTIFICATIONS_URL must use https for " + host
                    + " (plain http is allowed only for localhost or a compose service)");
        }
    }
}
