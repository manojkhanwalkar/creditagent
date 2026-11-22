package com.creditagent.web.subscriptions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Subscriptions ({@code credit.subscriptions.*}).
 *
 * @param provider who takes payments: {@code none} (default: nothing can be bought) or {@code test}
 *     (local development only: every payment succeeds and nothing is charged)
 * @param table the Subscriptions DynamoDB table
 * @param currency ISO 4217 code the prices are in
 * @param idProtectionPriceCents the price of a year of ID Protection, in cents (a placeholder until
 *     pricing is decided)
 */
@ConfigurationProperties("credit.subscriptions")
public record SubscriptionProperties(Provider provider, String table, String currency, Long idProtectionPriceCents) {

    public enum Provider { NONE, TEST }

    public SubscriptionProperties {
        provider = provider == null ? Provider.NONE : provider;
        table = table == null || table.isBlank() ? "credit-agent-subscriptions" : table;
        currency = currency == null || currency.isBlank() ? "USD" : currency.strip().toUpperCase(java.util.Locale.ROOT);
        idProtectionPriceCents = idProtectionPriceCents == null ? 2999L : idProtectionPriceCents;
        if (idProtectionPriceCents <= 0) {
            throw new IllegalArgumentException("credit.subscriptions.id-protection-price-cents must be positive");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("credit.subscriptions.currency must be an ISO 4217 code such as USD");
        }
    }
}
