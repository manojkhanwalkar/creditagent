package com.creditagent.web.offers;

import com.creditagent.web.auth.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/offers}: the signed-in user's card offers, as at login. The page calls it when it
 * resumes a session and after a score look-up records a new score. Needs the bearer token.
 */
@RestController
class OffersController {

    static final String PATH = "/api/v1/offers";

    private final OffersService offers;

    OffersController(OffersService offers) {
        this.offers = offers;
    }

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    OffersService.View offers(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return offers.offersFor(user.userId());
    }
}
