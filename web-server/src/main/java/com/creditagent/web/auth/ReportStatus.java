package com.creditagent.web.auth;

/**
 * Whether the user has locked their credit report. Stored per user ({@code reportStatus} in the
 * users table). New accounts start {@link #UNLOCKED}, and rows written before the attribute existed
 * read as {@link #UNLOCKED}.
 */
public enum ReportStatus {
    LOCKED,
    UNLOCKED;

    /** The stored value, or {@link #UNLOCKED} when the attribute is missing or unknown. */
    static ReportStatus fromStored(String value) {
        return "LOCKED".equals(value) ? LOCKED : UNLOCKED;
    }
}
