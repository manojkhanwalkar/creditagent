package com.creditagent.clients;

import com.creditagent.common.SafeLogging;
import com.creditagent.common.Ssn;
import org.slf4j.LoggerFactory;

/**
 * Asks whether a consumer has locked their credit report. Third-party consumers of reports (the
 * Credit Intelligence Agent, the MCP {@code get_credit_report} tool) check this before every pull;
 * the owner's own look-ups through the web server do not.
 */
@FunctionalInterface
public interface ReportLockCheck {

    /** What every third-party path tells a caller whose report pull was refused because of a lock. */
    String LOCKED_MESSAGE = "This credit report is locked by its owner.";

    /** What a third party may do with a report, after the lock check. */
    enum Decision {
        /** Not locked: the report may be read. */
        RELEASE,
        /** Locked by its owner: refuse with {@link #LOCKED_MESSAGE}. */
        LOCKED,
        /** The lock status is unknown: refuse (fail closed). */
        UNAVAILABLE
    }

    /**
     * @return true if the SSN's owner has locked their report; false if it is unlocked or no account
     *     exists for the SSN
     * @throws ServiceUnavailableException if the lock status cannot be determined. Callers must then
     *     refuse the pull (fail closed): a lock that silently stops working is worse than an outage.
     */
    boolean isLocked(Ssn ssn);

    /**
     * {@link #isLocked} as a decision that fails closed: a check that cannot be completed is
     * {@link Decision#UNAVAILABLE} (logged without detail), never a release.
     */
    default Decision decide(Ssn ssn) {
        try {
            return isLocked(ssn) ? Decision.LOCKED : Decision.RELEASE;
        } catch (ServiceUnavailableException e) {
            LoggerFactory.getLogger(ReportLockCheck.class).warn("Report lock check failed: {}", SafeLogging.describe(e));
            return Decision.UNAVAILABLE;
        }
    }
}
