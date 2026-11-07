package com.creditagent.score;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;

/** Computes a credit score from a credit report. */
public interface ScoreCalculator {

    CreditScore calculate(CreditReport report);
}
