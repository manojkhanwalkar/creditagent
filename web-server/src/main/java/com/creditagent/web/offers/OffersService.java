package com.creditagent.web.offers;

import com.creditagent.clients.ServiceUnavailableException;
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

    public OffersService(AuthService auth, OffersClient client) {
        this.auth = Objects.requireNonNull(auth);
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public View offersFor(String userId) {
        Optional<User> account = auth.account(userId);
        if (account.isEmpty() || account.get().lastScore() == null) {
            return View.of(Status.NO_SCORE);
        }
        User user = account.get();
        try {
            JsonNode answer = client.offers(user.lastScore(), user.zip());
            ArrayNode offers = (ArrayNode) answer.path("offers");
            while (offers.size() > MAX_OFFERS) {
                offers.remove(offers.size() - 1);
            }
            return new View(Status.OK, user.lastScore(), user.lastScoreAt(), user.zip(), offers,
                    answer.path("disclaimer").asString(null));
        } catch (ServiceUnavailableException e) {
            log.warn("Offers unavailable: {}", SafeLogging.describe(e));
            return View.of(Status.UNAVAILABLE);
        }
    }
}
