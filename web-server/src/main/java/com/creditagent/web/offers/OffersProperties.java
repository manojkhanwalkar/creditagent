package com.creditagent.web.offers;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Card offers ({@code credit.offers.*}).
 *
 * @param url the Offers service, e.g. {@code http://localhost:8090}
 * @param timeout per try for {@code GET /api/v1/offers}, which retries transient failures
 * @param loginTimeout the one try a login waits for offers: login never retries them, so a slow or
 *     hung Offers service costs a login at most this, and the page fetches them again later
 */
@ConfigurationProperties("credit.offers")
public record OffersProperties(String url, Duration timeout, Duration loginTimeout) {

    public OffersProperties {
        url = url == null || url.isBlank() ? "http://localhost:8090" : url;
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        loginTimeout = loginTimeout == null ? Duration.ofMillis(1500) : loginTimeout;
    }
}
