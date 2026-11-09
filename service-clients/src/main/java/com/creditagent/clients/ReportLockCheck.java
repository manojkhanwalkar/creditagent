package com.creditagent.clients;

import com.creditagent.common.Ssn;

/**
 * Asks whether a consumer has locked their credit report. Third-party consumers of reports (the
 * Credit Intelligence Agent, the MCP {@code get_credit_report} tool) check this before every pull;
 * the owner's own look-ups through the web server do not.
 */
@FunctionalInterface
public interface ReportLockCheck {

    /**
     * @return true if the SSN's owner has locked their report; false if it is unlocked or no account
     *     exists for the SSN
     * @throws ServiceUnavailableException if the lock status cannot be determined. Callers must then
     *     refuse the pull (fail closed): a lock that silently stops working is worse than an outage.
     */
    boolean isLocked(Ssn ssn);
}
