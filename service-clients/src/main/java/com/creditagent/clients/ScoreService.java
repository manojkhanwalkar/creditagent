package com.creditagent.clients;

import com.creditagent.common.CreditReport;
import com.creditagent.common.CreditScore;

/** Computes a score for a report. Production implementation: {@link ScoreCalculatorHttpClient}. */
public interface ScoreService {

    /** @throws ServiceUnavailableException if the score calculator cannot be reached */
    CreditScore score(CreditReport report);
}
