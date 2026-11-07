package com.creditagent.client;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;

/**
 * The remote Credit Intelligence Agent, as seen through LangChain4j's agentic A2A client. The
 * argument is sent as the text of an A2A message; the reply text is returned.
 */
public interface CreditAdvisorAgent {

    @Agent(description = "Returns a credit score and recommendations to raise it, as JSON", outputKey = "advice")
    String advise(@V("ssn") String ssn);
}
