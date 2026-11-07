package com.creditagent.web;

import com.creditagent.common.SafeLogging;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditScore;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.ScoreBand;
import com.creditagent.common.Ssn;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the UI. The SSN travels in a POST body, never in the URL, so it does not end up in
 * access logs, browser history or Referer headers.
 */
@RestController
@RequestMapping(path = "/api/v1/credit-score", produces = MediaType.APPLICATION_JSON_VALUE)
class CreditScoreController {

    private static final Logger log = LoggerFactory.getLogger(CreditScoreController.class);

    private final CreditScoreService service;

    CreditScoreController(CreditScoreService service) {
        this.service = service;
    }

    record CreditScoreRequest(@NotBlank @Size(max = 20) String ssn) {
        @Override
        public String toString() {
            return "CreditScoreRequest[ssn=<redacted>]";
        }
    }

    record CreditScoreResponse(String ssnMasked, int score, int maxScore, ScoreBand band) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    CreditScoreResponse creditScore(@Valid @RequestBody CreditScoreRequest request) {
        Ssn ssn = Ssn.parse(request.ssn());
        CreditScore score = service.scoreFor(ssn);
        return new CreditScoreResponse(ssn.masked(), score.value(), score.max(), score.band());
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
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
