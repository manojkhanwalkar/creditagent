package com.creditagent.web.chat;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.SafeLogging;
import com.creditagent.common.Ssn;
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
 * {@code POST /api/v1/chat} with {@code {ssn, message}}: a question about the signed-in user's own
 * credit report. Needs the bearer token (see {@code AuthenticationFilter}); any SSN but the user's own
 * gets 403 before a report is fetched. The SSN travels in the body, never the URL, and is never logged.
 */
@RestController
@RequestMapping(path = ChatController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class ChatController {

    static final String PATH = "/api/v1/chat";

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    /** {@code message} up to 1000 characters, the agent's own limit for a question. */
    record ChatRequest(@NotBlank @Size(max = 20) String ssn, @NotBlank @Size(max = 1000) String message) {
        @Override
        public String toString() {
            return "ChatRequest[ssn=<redacted>, message=<redacted>]";
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    AdvisorClient.ChatReply chat(
            @Valid @RequestBody ChatRequest request,
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return chat.chat(user, Ssn.parse(request.ssn()), request.message());
    }

    @ExceptionHandler(InvalidSsnException.class)
    ProblemDetail invalidSsn(InvalidSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotOwnSsnException.class)
    ProblemDetail notOwnSsn(NotOwnSsnException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(ChatService.NoReportException.class)
    ProblemDetail noReport(ChatService.NoReportException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No credit report was found for your SSN.");
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ProblemDetail unavailable(ServiceUnavailableException e) {
        log.warn("Chat downstream failure: {}", SafeLogging.describe(e));
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The assistant is unavailable right now. Please try again shortly.");
    }
}
