package com.creditagent.web.auth;

import org.slf4j.LoggerFactory;

/**
 * Whether the user has locked their credit report. Stored per user ({@code reportStatus} in the
 * users table). New accounts start {@link #UNLOCKED}, and rows written before the attribute existed
 * read as {@link #UNLOCKED}.
 */
public enum ReportStatus {
    LOCKED,
    UNLOCKED;

    /**
     * The stored value. A missing attribute (an account created before locks existed) is
     * {@link #UNLOCKED}. Any other unexpected value is {@link #LOCKED}: a lock must fail closed, so a
     * mistyped or corrupt value never silently unlocks a report. It is logged so it can be repaired.
     */
    static ReportStatus fromStored(String value) {
        if (value == null) {
            return UNLOCKED;
        }
        return switch (value) {
            case "LOCKED" -> LOCKED;
            case "UNLOCKED" -> UNLOCKED;
            default -> {
                LoggerFactory.getLogger(ReportStatus.class)
                        .warn("Unknown stored reportStatus; treating the report as locked");
                yield LOCKED;
            }
        };
    }
}
