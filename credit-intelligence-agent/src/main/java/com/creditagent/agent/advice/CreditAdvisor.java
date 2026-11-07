package com.creditagent.agent.advice;

import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;

/** Turns a report and its score into recommendations for raising the score. */
public interface CreditAdvisor {

    CreditAdvice advise(CreditReport report, CreditScore score);
}
