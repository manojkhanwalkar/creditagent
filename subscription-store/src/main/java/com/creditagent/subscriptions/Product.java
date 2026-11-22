package com.creditagent.subscriptions;

import java.util.Locale;
import java.util.Optional;

/** The paid products a user can subscribe to. Each subscription runs for a year. */
public enum Product {
    /** Dark-web alerts shown in full, and emailed. */
    ID_PROTECTION,
    /** Daily credit-score monitoring with alerts on a change (not offered yet). */
    CREDIT_MONITORING;

    /** The product named by {@code name} in any case ({@code "id_protection"}, {@code "ID_PROTECTION"}). */
    public static Optional<Product> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
