package com.creditagent.web;

import com.creditagent.common.SafeLogging;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditScore;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AuthenticatedUser;
import com.creditagent.web.auth.NotOwnSsnException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the UI. The SSN travels in a POST body, never in the URL, so it does not end up in
 * access logs, browser history or Referer headers. A signed-in user may only look up their own
 * SSN, the one they registered with; any other SSN gets 403 before a report is fetched.
 */
@RestController
@RequestMapping(path = "/api/v1/credit-score", produces = MediaType.APPLICATION_JSON_VALUE)
class CreditScoreController {

    private static final Logger log = LoggerFactory.getLogger(CreditScoreController.class);

    private final CreditScoreService service;
    private final AuthService auth;

    CreditScoreController(CreditScoreService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    record CreditScoreRequest(@NotBlank @Size(max = 20) String ssn) {
        @Override
        public String toString() {
            return "CreditScoreRequest[ssn=<redacted>]";
        }
    }

    record CreditScoreResponse(String ssnMasked, int score, int maxScore, ScoreBand band) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    CreditScoreResponse creditScore(
            @Valid @RequestBody CreditScoreRequest request,
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        Ssn ssn = Ssn.parse(request.ssn());
        auth.requireOwnSsn(user, ssn);
        CreditScore score = service.scoreFor(ssn);
        // Kept for offers at the next login; the SSN itself is never stored. A failed write must not
        // turn a score the user already has into an error.
        try {
            auth.recordScore(user.userId(), score.value());
        } catch (RuntimeException e) {
            log.warn("Could not record the score: {}", SafeLogging.describe(e));
        }
        return new CreditScoreResponse(ssn.masked(), score.value(), score.max(), score.band());
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotOwnSsnException.class)
    ProblemDetail notOwnSsn(NotOwnSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(CreditScoreService.CreditReportNotFoundException.class)
    ProblemDetail notFound(CreditScoreService.CreditReportNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No credit report was found for that SSN.");
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ProblemDetail unavailable(ServiceUnavailableException e) {
        log.warn("Downstream failure: {}", SafeLogging.describe(e));
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The credit service is temporarily unavailable. Please try again.");
    }
}
