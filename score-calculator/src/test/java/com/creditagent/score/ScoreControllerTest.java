package com.creditagent.score;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.CreditScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScoreController.class)
class ScoreControllerTest {

    static final String REPORT_JSON = """
            {
              "reportId": "r-1",
              "ssnMasked": "***-**-6789",
              "fullName": "Avery Patel",
              "generatedAt": "2026-11-01T12:00:00Z",
              "accounts": [
                {"type": "CREDIT_CARD", "creditor": "Northwind Bank", "status": "OPEN",
                 "balance": 250, "creditLimit": 5000, "monthsOpen": 48,
                 "latePayments30": 0, "latePayments60": 0, "latePayments90": 0}
              ],
              "hardInquiriesLast12Months": 1,
              "collections": 0,
              "publicRecords": 0
            }
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ScoreCalculator calculator;

    @Test
    void returnsTheCalculatedScoreWithItsBand() throws Exception {
        given(calculator.calculate(any())).willReturn(CreditScore.of(712));

        mvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content(REPORT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(712))
                .andExpect(jsonPath("$.band").value("GREEN"))
                .andExpect(jsonPath("$.max").value(850));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonJsonContent() throws Exception {
        mvc.perform(post("/api/v1/scores").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
