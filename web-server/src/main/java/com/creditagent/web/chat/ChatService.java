package com.creditagent.web.chat;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AuthenticatedUser;
import com.creditagent.web.auth.SessionEndListener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * The chat: checks the SSN is the user's own, fetches their report, and passes the question with it to
 * the User Advisor Agent. The conversation is keyed by a hash of the login session, so each login
 * starts a fresh conversation, the agent never learns who the user is, and the key cannot be used as a
 * session id. Logging out tells the agent to forget the conversation.
 */
public class ChatService implements SessionEndListener {

    /** No credit report is on file for the user's SSN. */
    public static class NoReportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoReportException() {
            super("No credit report on file", null, false, false);
        }
    }

    private final AuthService auth;
    private final CreditReportSource reports;
    private final AdvisorClient advisor;

    public ChatService(AuthService auth, CreditReportSource reports, AdvisorClient advisor) {
        this.auth = Objects.requireNonNull(auth);
        this.reports = Objects.requireNonNull(reports);
        this.advisor = Objects.requireNonNull(advisor);
    }

    /**
     * @throws com.creditagent.web.auth.NotOwnSsnException if {@code ssn} is not the user's own
     * @throws NoReportException if no report is on file
     * @throws com.creditagent.clients.ServiceUnavailableException if the report or the agent is unavailable
     */
    public AdvisorClient.ChatReply chat(AuthenticatedUser user, Ssn ssn, String message) {
        auth.requireOwnSsn(user, ssn);
        CreditReport report = reports.findReport(ssn).orElseThrow(NoReportException::new);
        return advisor.chat(conversationId(user), message, report);
    }

    @Override
    public void sessionEnded(AuthenticatedUser user) {
        advisor.forget(conversationId(user));
    }

    static String conversationId(AuthenticatedUser user) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("chat:" + user.sessionId()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
