package com.creditagent.web.alerts;

import com.creditagent.web.auth.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/alerts}: the signed-in user's dark-web alerts, as at login. Needs the bearer token. */
@RestController
class AlertsController {

    static final String PATH = "/api/v1/alerts";

    private final AlertsService alerts;

    AlertsController(AlertsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    AlertsService.View alerts(@RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return alerts.current(user.userId());
    }
}
