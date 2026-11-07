package com.creditagent.client;

/** Sends an SSN to the advisor and returns its raw reply. Seam for testing without a remote agent. */
public interface CreditAdvisorPort {

    String requestAdvice(String ssnDigits);
}
