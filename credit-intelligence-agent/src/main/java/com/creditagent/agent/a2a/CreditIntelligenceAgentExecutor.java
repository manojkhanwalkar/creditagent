package com.creditagent.agent.a2a;

import com.creditagent.common.SafeLogging;
import com.creditagent.agent.CreditIntelligenceService;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles an A2A message: finds the SSN in the text, runs the credit analysis, and replies with a
 * single message whose text is the {@link CreditAdvice} as JSON.
 *
 * <p>Failures are replied as {@code {"error": "..."}} so the client always gets a parseable answer.
 * Neither the user input nor the SSN is ever logged.
 */
public class CreditIntelligenceAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(CreditIntelligenceAgentExecutor.class);
    private static final Pattern SSN_IN_TEXT = Pattern.compile("(?<!\\d)(\\d{3}-?\\d{2}-?\\d{4})(?!\\d)");

    private final CreditIntelligenceService service;
    private final JsonMapper jsonMapper;

    public CreditIntelligenceAgentExecutor(CreditIntelligenceService service, JsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        Optional<String> candidate = extractSsn(context.getUserInput());
        if (candidate.isEmpty()) {
            emitter.sendMessage(error("Send a 9-digit SSN (AAA-GG-SSSS) to get recommendations."));
            return;
        }
        try {
            Ssn ssn = Ssn.parse(candidate.get());
            CreditAdvice advice = service.advise(ssn);
            log.info("Sent {} recommendations for {}", advice.recommendations().size(), ssn.masked());
            emitter.sendMessage(jsonMapper.writeValueAsString(advice));
        } catch (InvalidSsnException e) {
            emitter.sendMessage(error(e.getMessage()));
        } catch (CreditIntelligenceService.ReportNotFoundException e) {
            emitter.sendMessage(error("No credit report was found for that SSN."));
        } catch (ServiceUnavailableException e) {
            log.warn("Downstream failure: {}", SafeLogging.describe(e));
            emitter.sendMessage(error("The credit service is temporarily unavailable. Please try again."));
        }
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        throw new UnsupportedOperationError();
    }

    static Optional<String> extractSsn(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = SSN_IN_TEXT.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String error(String message) {
        return jsonMapper.writeValueAsString(Map.of("error", message));
    }
}
