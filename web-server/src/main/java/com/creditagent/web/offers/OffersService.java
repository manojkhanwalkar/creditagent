package com.creditagent.web.offers;

import com.creditagent.common.SafeLogging;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.LoginOffers;
import com.creditagent.web.auth.User;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The user's card offers: their last recorded credit score and their ZIP code go to the Offers
 * service, which ranks up to 10 offers. Called at login ({@link LoginOffers}) and by
 * {@code GET /api/v1/offers}. Never fails the caller: without a score, or when the service is down,
 * the answer says so in {@link View#status()}.
 */
public class OffersService implements LoginOffers {

    private static final Logger log = LoggerFactory.getLogger(OffersService.class);

    static final int MAX_OFFERS = 10;
    /** Shown whenever offers are, even if the Offers service sent no disclaimer of its own. */
    static final String DEFAULT_DISCLAIMER = "These are not firm offers of credit or pre-approvals. Approval, credit"
            + " limit and APR depend on the issuer's review of your application; confirm the terms with the issuer.";

    public enum Status {
        /** Offers were found (the list may still be empty). */
        OK,
        /** No credit score is recorded yet: look up the score once, then offers follow. */
        NO_SCORE,
        /** The Offers service could not answer. */
        UNAVAILABLE
    }

    /**
     * What the UI shows.
     *
     * @param score the recorded score the offers were matched on
     * @param scoredAt when that score was computed
     * @param offers the Offers service's offers, at most {@link #MAX_OFFERS}, best first
     */
    public record View(Status status, Integer score, Instant scoredAt, String zip, JsonNode offers, String disclaimer) {
        static View of(Status status) {
            return new View(status, null, null, null, null, null);
        }
    }

    private final AuthService auth;
    private final OffersClient client;
    private final OffersClient loginClient;

    /**
     * @param client for {@code GET /api/v1/offers}: retries transient failures
     * @param loginClient for login: one short try, so a slow Offers service cannot hold up a login
     */
    public OffersService(AuthService auth, OffersClient client, OffersClient loginClient) {
        this.auth = Objects.requireNonNull(auth);
        this.client = Objects.requireNonNull(client);
        this.loginClient = Objects.requireNonNull(loginClient);
    }

    /** At login: one short try; never fails the login. */
    @Override
    public View offersFor(String userId) {
        return offers(userId, loginClient);
    }

    /** For {@code GET /api/v1/offers}: retried; never throws. */
    public View current(String userId) {
        return offers(userId, client);
    }

    private View offers(String userId, OffersClient via) {
        try {
            return fetch(userId, via);
        } catch (RuntimeException e) {
            // The account read or the call failed: the caller (a login) must still succeed.
            log.warn("Offers unavailable: {}", SafeLogging.describe(e));
            return View.of(Status.UNAVAILABLE);
        }
    }

    private View fetch(String userId, OffersClient via) {
        Optional<User> account = auth.account(userId);
        if (account.isEmpty() || account.get().lastScore() == null) {
            return View.of(Status.NO_SCORE);
        }
        User user = account.get();
        JsonNode answer = via.offers(user.lastScore(), user.zip());
        ArrayNode offers = (ArrayNode) answer.path("offers");
        while (offers.size() > MAX_OFFERS) {
            offers.remove(offers.size() - 1);
        }
        String disclaimer = answer.path("disclaimer").asString("");
        return new View(Status.OK, user.lastScore(), user.lastScoreAt(), user.zip(), offers,
                disclaimer.isBlank() ? DEFAULT_DISCLAIMER : disclaimer);
    }
}
