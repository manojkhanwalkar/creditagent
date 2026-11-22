package com.creditagent.web.notify;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Where sign-in codes are sent from ({@code credit.notifications}).
 *
 * @param mode {@code local} (SES and SNS from this process, the default, also when blank) or
 *     {@code remote} (through the notification-service; SES and SNS are then not built)
 * @param url the notification-service base URL: absolute http(s) with a host, and https unless the host
 *     is local ({@code localhost}, {@code 127.0.0.1}, {@code ::1}) or a compose service name (no dot), so
 *     codes and the service token never cross a network in clear text
 * @param timeout longest one try may take; above the service's own worst case (about 8 s)
 * @param connectTimeout longest opening a connection may take; short, so an unreachable service fails
 *     fast even with the connect retries
 */
@ConfigurationProperties("credit.notifications")
public record NotificationsProperties(Mode mode, String url, Duration timeout, Duration connectTimeout) {

    public static final String MODE = "credit.notifications.mode";

    public enum Mode { LOCAL, REMOTE }

    /** Hosts where plain http is acceptable: this machine. A name without a dot is a compose service. */
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public NotificationsProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        url = url == null || url.isBlank() ? "http://localhost:8094" : url.strip();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        if (!timeout.isPositive() || !connectTimeout.isPositive()) {
            throw new IllegalArgumentException("credit.notifications.timeout and connect-timeout must be positive");
        }
        requireSafeUrl(url);
    }

    /** The mode as Spring binds it, so a blank value means local here too (used before beans exist). */
    public static Mode mode(Environment environment) {
        return Binder.get(environment).bind(MODE, Mode.class).orElse(Mode.LOCAL);
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
