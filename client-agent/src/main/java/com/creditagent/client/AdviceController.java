package com.creditagent.client;

import com.creditagent.common.SafeLogging;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.InvalidSsnException;
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

/** Entry point for the agent flow (A in the diagram): POST an SSN, get recommendations back. */
@RestController
@RequestMapping(path = "/api/v1/advice", produces = MediaType.APPLICATION_JSON_VALUE)
class AdviceController {

    private static final Logger log = LoggerFactory.getLogger(AdviceController.class);

    private final CreditAdviceClient client;

    AdviceController(CreditAdviceClient client) {
        this.client = client;
    }

    record AdviceRequest(@NotBlank @Size(max = 20) String ssn) {
        @Override
        public String toString() {
            return "AdviceRequest[ssn=<redacted>]";
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    CreditAdvice advice(@Valid @RequestBody AdviceRequest request) {
        return client.adviceFor(Ssn.parse(request.ssn()));
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(CreditAdviceClient.AgentRejectedException.class)
    ProblemDetail rejected(CreditAdviceClient.AgentRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(CreditAdviceClient.AgentCallException.class)
    ProblemDetail unavailable(CreditAdviceClient.AgentCallException e) {
        log.warn("A2A call failed: {}", SafeLogging.describe(e));
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The Credit Intelligence Agent is unavailable. Please try again.");
    }
}
