package com.creditagent.nrg;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "nrg.max-request-bytes=4096")
@AutoConfigureMockMvc
class NormalizeControllerTest {

    static final String EQUIFAX = """
            {"reportNumber":"EFX-1","dateOfReport":"11/07/2026",
             "subject":{"firstName":"Avery","lastName":"Patel","ssnLast4":"6789"},
             "trades":[{"memberName":"Northwind Bank","accountNumber":"************1234","portfolioType":"R",
                        "accountType":"18","dateOpened":"11/2023","dateReported":"11/05/2026","highCredit":1500,
                        "creditLimit":5000,"balance":1200,"rate":"R1","times30DaysLate":0,"times60DaysLate":0,
                        "times90DaysLate":0,"closed":false}],
             "inquiries":[],"collectionItems":0,"publicRecordItems":0}
            """;

    static final String TRANSUNION = """
            {"fileNumber":"TU-1","reportDate":"2026-11-07","name":{"first":"Avery","last":"Patel"},
             "ssnMasked":"***-**-6789",
             "trades":[{"subscriberName":"Northwind Bank","accountNumber":"1234****","industryCode":"BC",
                        "portfolioType":"revolving","dateOpened":"2023-11-07","dateEffective":"2026-10-01",
                        "currentBalance":900,"creditLimit":5000,"highCredit":1500,"accountRating":"01",
                        "historicalCounters":{"late30":0,"late60":0,"late90":0}}],
             "inquiries":[{"subscriberName":"Fabrikam Credit","inquiryDate":"2026-08-15"}],
             "collectionsCount":0,"publicRecordsCount":0}
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void returnsOneNormalizedReport() throws Exception {
        mvc.perform(post("/api/v1/normalized-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equifax\":" + EQUIFAX + ",\"transunion\":" + TRANSUNION + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"))
                .andExpect(jsonPath("$.fullName").value("Avery Patel"))
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].balance").value(1200))
                .andExpect(jsonPath("$.hardInquiriesLast12Months").value(1));
    }

    @Test
    void emptyRequestIs422() throws Exception {
        mvc.perform(post("/api/v1/normalized-reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void invalidFieldIs422WithoutEchoingTheValue() throws Exception {
        String bad = EQUIFAX.replace("\"11/07/2026\"", "\"not-a-date-6789\"");
        mvc.perform(post("/api/v1/normalized-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equifax\":" + bad + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(Matchers.not(Matchers.containsString("not-a-date"))));
    }

    @Test
    void oversizedBodiesAreRefused() throws Exception {
        mvc.perform(post("/api/v1/normalized-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pad\":\"" + "x".repeat(5000) + "\"}"))
                .andExpect(status().is(413));
    }
}
