package com.creditagent.score;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Odd but parseable reports go through the real calculator and get a score, not a 500. */
@WebMvcTest(ScoreController.class)
@Import(ScoreCalculatorConfiguration.class)
class ScoreControllerUntrustedInputTest {

    @Autowired
    MockMvc mvc;

    @Test
    void scoresAReportWithNullsAndNegativeValues() throws Exception {
        String json = """
                {
                  "accounts": [
                    {"type": null, "status": null, "balance": -500, "creditLimit": -1, "monthsOpen": -12,
                     "latePayments30": -3, "latePayments60": 0, "latePayments90": 0},
                    {"type": "CREDIT_CARD", "status": "OPEN", "balance": 9223372036854775807,
                     "creditLimit": 9223372036854775807, "monthsOpen": 2147483647,
                     "latePayments30": 2147483647, "latePayments60": 2147483647, "latePayments90": 2147483647},
                    {"type": "CREDIT_CARD", "balance": 100, "creditLimit": 0, "monthsOpen": 30,
                     "latePayments30": 0, "latePayments60": 0, "latePayments90": 0}
                  ],
                  "hardInquiriesLast12Months": -1,
                  "collections": -2147483648,
                  "publicRecords": 2147483647
                }
                """;
        mvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").isNumber())
                .andExpect(jsonPath("$.max").value(850));
    }

    @Test
    void scoresAReportWithNullAccountsAsAnEmptyFile() throws Exception {
        String json = """
                {"accounts": null, "hardInquiriesLast12Months": 0, "collections": 0, "publicRecords": 0}
                """;
        mvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(548));
    }

    @Test
    void rejectsMissingCountsAsABadRequest() throws Exception {
        // Jackson refuses null or missing primitives, so this is a 400, never a 500.
        mvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsANullAccountEntryAsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content("""
                        {"accounts": [null], "hardInquiriesLast12Months": 0, "collections": 0, "publicRecords": 0}
                        """))
                .andExpect(status().isBadRequest());
    }
}
