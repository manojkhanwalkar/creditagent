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
import java.util.concurrent.Executor;
import com.creditagent.common.SafeLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The chat: checks the SSN is the user's own, fetches their report, and passes the question with it to
 * the User Advisor Agent. The conversation is keyed by a hash of the login session, so each login
 * starts a fresh conversation, the agent never learns who the user is, and the key cannot be used as a
 * session id. Logging out tells the agent to forget the conversation.
 */
public class ChatService implements SessionEndListener {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

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
    private final Executor forgetter;

    public ChatService(AuthService auth, CreditReportSource reports, AdvisorClient advisor) {
        this(auth, reports, advisor, task -> Thread.ofVirtual().name("chat-forget").start(task));
    }

    /** @param forgetter runs the logout-time forget off the request thread */
    public ChatService(AuthService auth, CreditReportSource reports, AdvisorClient advisor, Executor forgetter) {
        this.auth = Objects.requireNonNull(auth);
        this.reports = Objects.requireNonNull(reports);
        this.advisor = Objects.requireNonNull(advisor);
        this.forgetter = Objects.requireNonNull(forgetter);
    }

    /**
     * @throws com.creditagent.web.auth.NotOwnSsnException if {@code ssn} is not the user's own
     * @throws NoReportException if no report is on file
     * @throws com.creditagent.clients.ServiceUnavailableException if the report or the agent is unavailable
     */
    public AdvisorClient.ChatReply chat(AuthenticatedUser user, Ssn ssn, String message) {
        auth.requireOwnSsn(user, ssn);
        CreditReport report = reports.findReport(ssn).orElseThrow(NoReportException::new);
        return advisor.chat(conversationId(user), message, withoutIdentity(report));
    }

    /**
     * The report without the fields that say whose it is (name, masked SSN, report id): the agent, and
     * a model behind it, need the accounts and counts, not the person.
     */
    static CreditReport withoutIdentity(CreditReport report) {
        return new CreditReport(null, null, null, report.generatedAt(), report.accounts(),
                report.hardInquiriesLast12Months(), report.collections(), report.publicRecords());
    }

    /**
     * Tells the agent to forget the conversation, in the background: a slow or unreachable agent must
     * not hold up the logout response, and the conversation expires on its own anyway.
     */
    @Override
    public void sessionEnded(AuthenticatedUser user) {
        String conversation = conversationId(user);
        forgetter.execute(() -> {
            try {
                advisor.forget(conversation);
            } catch (RuntimeException e) {
                log.warn("Could not tell the advisor to forget a conversation: {}", SafeLogging.describe(e));
            }
        });
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
