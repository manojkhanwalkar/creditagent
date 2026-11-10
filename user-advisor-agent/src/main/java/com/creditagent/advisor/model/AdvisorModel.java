package com.creditagent.advisor.model;

import com.creditagent.advisor.memory.ConversationMemory.Turn;
import com.creditagent.common.CreditReport;
import java.util.List;

/**
 * Where an answer comes from. The placeholder answers from the report by rules; a real LLM goes here
 * later, taking {@link PromptBuilder}'s prompt. Every implementation sits between the same input and
 * output guardrails, so it never sees an unchecked question and its answer is always checked.
 */
public interface AdvisorModel {

    /**
     * @param question the user's question, already through the input guardrails
     * @param history earlier turns of this conversation, oldest first
     * @param report the user's own credit report
     */
    String answer(String question, List<Turn> history, CreditReport report);
}
