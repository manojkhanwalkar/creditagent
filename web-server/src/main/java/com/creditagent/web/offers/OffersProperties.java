package com.creditagent.web.offers;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Card offers ({@code credit.offers.*}).
 *
 * @param url the Offers service, e.g. {@code http://localhost:8090}
 * @param timeout per try; offers are computed in memory, so this is short. Login waits for it, so a
 *     slow Offers service costs every login at most this (times the retries).
 */
@ConfigurationProperties("credit.offers")
public record OffersProperties(String url, Duration timeout) {

    public OffersProperties {
        url = url == null || url.isBlank() ? "http://localhost:8090" : url;
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }
}
