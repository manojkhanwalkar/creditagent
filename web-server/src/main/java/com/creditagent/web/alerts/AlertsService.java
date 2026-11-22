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
 *
 * <p>The findings are for ID Protection subscribers. A free user whose email was found is told that
 * something was found, with no details ({@code locked}), and offered the subscription. A data export
 * ({@link #findingsFor}) always includes every finding: access to one's own data is never paid for.
 */
public class AlertsService implements LoginAlerts {

    private static final Logger log = LoggerFactory.getLogger(AlertsService.class);

    static final String FOUND = "Your information was found on the dark web. Someone may try to use it to open"
            + " accounts or get into yours. Change the passwords you use with this email, and watch your credit"
            + " report for accounts you did not open.";
    static final String FOUND_LOCKED = "Something connected to your email was found on the dark web. Change the"
            + " passwords you use with this email, and watch your credit report for accounts you did not open. Where"
            + " and when it was found is listed in your data download; subscribe to ID Protection to see it here and"
            + " get an email each time it happens.";
    static final int MAX_FINDINGS = 20;

    public enum Status { OK, UNAVAILABLE }

    /**
     * What the page shows. {@code message} is set only when something was found. {@code locked}: something
     * was found, but the user has no ID Protection, so {@code findings} is empty.
     */
    public record View(Status status, boolean exposed, List<AlertsClient.Finding> findings, String message, boolean locked) {
        static View unavailable() {
            return new View(Status.UNAVAILABLE, false, List.of(), null, false);
        }

        static View clear() {
            return new View(Status.OK, false, List.of(), null, false);
        }
    }

    /** Whether the user has active ID Protection; may throw if it cannot be told. */
    @FunctionalInterface
    public interface Entitlement {
        boolean idProtection(String userId);
    }

    private final AuthService auth;
    private final AlertsClient client;
    private final AlertsClient loginClient;
    private final Entitlement entitlement;

    /**
     * @param client for {@code GET /api/v1/alerts}: retries transient failures
     * @param loginClient for login: one short try, so the Alerts service cannot hold up a login
     * @param entitlement who sees the findings
     */
    public AlertsService(AuthService auth, AlertsClient client, AlertsClient loginClient, Entitlement entitlement) {
        this.auth = Objects.requireNonNull(auth);
        this.client = Objects.requireNonNull(client);
        this.loginClient = Objects.requireNonNull(loginClient);
        this.entitlement = Objects.requireNonNull(entitlement);
    }

    @Override
    public View alertsFor(String userId) {
        return alerts(userId, loginClient);
    }

    public View current(String userId) {
        return alerts(userId, client);
    }

    /**
     * Every finding for the address, for a data export; unlike {@link #current} a failure is thrown,
     * so an export never silently leaves the alerts out.
     *
     * @throws com.creditagent.clients.ServiceUnavailableException if the service cannot answer
     */
    public List<AlertsClient.Finding> findingsFor(String email) {
        return client.lookup(email).findings();
    }

    /**
     * Deletes every alert for the address (account deletion).
     *
     * @throws com.creditagent.clients.ServiceUnavailableException if the service could not confirm it
     */
    public void erase(String email) {
        client.erase(email);
    }

    private View alerts(String userId, AlertsClient via) {
        try {
            Optional<User> account = auth.account(userId);
            if (account.isEmpty() || account.get().email() == null) {
                return View.clear();
            }
            AlertsClient.Lookup lookup = via.lookup(account.get().email());
            if (!lookup.exposed()) {
                return View.clear();
            }
            // Asked only when something was found, so most logins pay nothing for it. If it cannot be
            // told, nothing is shown (unavailable, retried by the page) rather than findings unpaid for.
            if (!entitlement.idProtection(userId)) {
                return new View(Status.OK, true, List.of(), FOUND_LOCKED, true);
            }
            List<AlertsClient.Finding> findings = lookup.findings().stream().limit(MAX_FINDINGS).toList();
            return new View(Status.OK, true, findings, FOUND, false);
        } catch (RuntimeException e) {
            log.warn("Alerts unavailable: {}", SafeLogging.describe(e));
            return View.unavailable();
        }
    }
}
