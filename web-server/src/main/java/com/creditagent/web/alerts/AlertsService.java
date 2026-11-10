package com.creditagent.web.alerts;

import com.creditagent.common.SafeLogging;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.LoginAlerts;
import com.creditagent.web.auth.User;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the signed-in user's email was found on the dark web: the user's own email goes to the
 * Alerts service, which reads the Alerts table the dark web monitor fills. Called at login
 * ({@link LoginAlerts}) and by {@code GET /api/v1/alerts}. Never fails the caller.
 */
public class AlertsService implements LoginAlerts {

    private static final Logger log = LoggerFactory.getLogger(AlertsService.class);

    static final String FOUND = "Your information was found on the dark web. Someone may try to use it to open"
            + " accounts or get into yours. We suggest you buy identity protection, which monitors your credit and"
            + " helps restore your identity if it is misused, and change the passwords you use with this email.";
    static final int MAX_FINDINGS = 20;

    public enum Status { OK, UNAVAILABLE }

    /** What the page shows. {@code message} is set only when something was found. */
    public record View(Status status, boolean exposed, List<AlertsClient.Finding> findings, String message) {
        static View unavailable() {
            return new View(Status.UNAVAILABLE, false, List.of(), null);
        }
    }

    private final AuthService auth;
    private final AlertsClient client;
    private final AlertsClient loginClient;

    /**
     * @param client for {@code GET /api/v1/alerts}: retries transient failures
     * @param loginClient for login: one short try, so the Alerts service cannot hold up a login
     */
    public AlertsService(AuthService auth, AlertsClient client, AlertsClient loginClient) {
        this.auth = Objects.requireNonNull(auth);
        this.client = Objects.requireNonNull(client);
        this.loginClient = Objects.requireNonNull(loginClient);
    }

    @Override
    public View alertsFor(String userId) {
        return alerts(userId, loginClient);
    }

    public View current(String userId) {
        return alerts(userId, client);
    }

    private View alerts(String userId, AlertsClient via) {
        try {
            Optional<User> account = auth.account(userId);
            if (account.isEmpty() || account.get().email() == null) {
                return new View(Status.OK, false, List.of(), null);
            }
            AlertsClient.Lookup lookup = via.lookup(account.get().email());
            if (!lookup.exposed()) {
                return new View(Status.OK, false, List.of(), null);
            }
            List<AlertsClient.Finding> findings = lookup.findings().stream().limit(MAX_FINDINGS).toList();
            return new View(Status.OK, true, findings, FOUND);
        } catch (RuntimeException e) {
            log.warn("Alerts unavailable: {}", SafeLogging.describe(e));
            return View.unavailable();
        }
    }
}
