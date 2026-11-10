package com.creditagent.advisor;

import com.creditagent.advisor.guard.InputGuard;
import com.creditagent.advisor.guard.OutputGuard;
import com.creditagent.advisor.memory.ConversationMemory;
import com.creditagent.advisor.memory.ConversationMemory.Turn;
import com.creditagent.advisor.model.AdvisorModel;
import com.creditagent.common.CreditReport;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One chat turn: input guardrails, then the model with the conversation so far, then output
 * guardrails, then memory. Only checked text is ever stored (OWASP ASI06): a refused question is not
 * remembered, and an answer is remembered as it was shown.
 */
public class AdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);

    /**
     * What the user sees.
     *
     * @param refused the question was refused by the input guardrails, or the answer replaced by the
     *     output guardrails
     */
    public record Reply(String reply, String disclaimer, boolean refused) {}

    private final InputGuard input;
    private final OutputGuard output;
    private final AdvisorModel model;
    private final ConversationMemory memory;

    public AdvisorService(InputGuard input, OutputGuard output, AdvisorModel model, ConversationMemory memory) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        this.model = Objects.requireNonNull(model);
        this.memory = Objects.requireNonNull(memory);
    }

    public Reply chat(String conversationId, String question, CreditReport report) {
        List<Turn> history = memory.history(conversationId);
        InputGuard.Verdict verdict = input.check(question, !history.isEmpty());
        if (!verdict.allowed()) {
            log.info("Question refused by the input guardrails: {}", verdict.refusal());
            return new Reply(verdict.refusal().message(), OutputGuard.DISCLAIMER, true);
        }
        OutputGuard.Result result = output.check(model.answer(verdict.text(), history, report));
        if (result.replaced()) {
            log.warn("Answer replaced by the output guardrails: {}", result.problem());
            return new Reply(result.reply(), result.disclaimer(), true);
        }
        memory.remember(conversationId, new Turn(verdict.text(), result.reply()));
        return new Reply(result.reply(), result.disclaimer(), false);
    }

    public void forget(String conversationId) {
        memory.forget(conversationId);
    }
}
