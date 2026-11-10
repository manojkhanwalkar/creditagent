package com.creditagent.advisor;

import com.creditagent.advisor.guard.InputGuard;
import com.creditagent.advisor.guard.OutputGuard;
import com.creditagent.advisor.memory.ConversationMemory;
import com.creditagent.advisor.memory.ConversationMemory.Turn;
import com.creditagent.advisor.model.AdvisorModel;
import com.creditagent.common.CreditReport;
import com.creditagent.advisor.eval.EvalLog;
import com.creditagent.advisor.eval.EvalRecord;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One chat turn: input guardrails, then the model with the conversation so far, then output
 * guardrails, then memory. Every turn, refused or not, is written to the {@link EvalLog} for offline
 * evaluation. Only checked text is ever stored (OWASP ASI06): a refused question is not
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
    private final EvalLog evals;
    private final Clock clock;

    public AdvisorService(InputGuard input, OutputGuard output, AdvisorModel model, ConversationMemory memory) {
        this(input, output, model, memory, EvalLog.NONE, Clock.systemUTC());
    }

    /** @param evals where every turn is recorded for offline evaluation (redacted; never the report) */
    public AdvisorService(InputGuard input, OutputGuard output, AdvisorModel model, ConversationMemory memory,
            EvalLog evals, Clock clock) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        this.model = Objects.requireNonNull(model);
        this.memory = Objects.requireNonNull(memory);
        this.evals = Objects.requireNonNull(evals);
        this.clock = Objects.requireNonNull(clock);
    }

    public Reply chat(String conversationId, String question, CreditReport report) {
        List<Turn> history = memory.history(conversationId);
        InputGuard.Verdict verdict = input.check(question, !history.isEmpty());
        String modelName = model.getClass().getSimpleName();
        if (!verdict.allowed()) {
            log.info("Question refused by the input guardrails: {}", verdict.refusal());
            Reply reply = new Reply(verdict.refusal().message(), OutputGuard.DISCLAIMER, true);
            evals.record(new EvalRecord(clock.instant(), conversationId, history.size(), EvalRecord.safe(question),
                    verdict.refusal().name(), null, reply.reply(), null, modelName, 0));
            return reply;
        }
        long started = System.nanoTime();
        String answer = model.answer(verdict.text(), history, report);
        long latencyMillis = (System.nanoTime() - started) / 1_000_000;
        OutputGuard.Result result = output.check(answer);
        evals.record(new EvalRecord(clock.instant(), conversationId, history.size(), verdict.text(),
                verdict.smallTalk() ? "SMALL_TALK" : "ALLOWED", EvalRecord.safe(answer), result.reply(),
                result.replaced() ? result.problem().name() : "PASSED", modelName, latencyMillis));
        if (result.replaced()) {
            log.warn("Answer replaced by the output guardrails: {}", result.problem());
            return new Reply(result.reply(), result.disclaimer(), true);
        }
        if (!verdict.smallTalk()) {
            // A greeting is not context: remembering it would let any short message pass as a follow-up.
            memory.remember(conversationId, new Turn(verdict.text(), result.reply()));
        }
        return new Reply(result.reply(), result.disclaimer(), false);
    }

    public void forget(String conversationId) {
        memory.forget(conversationId);
    }
}
