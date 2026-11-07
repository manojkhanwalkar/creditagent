package com.creditagent.generator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditReportService service;

    @Test
    void createsAReportAndReturnsOnlyItsIdAndMaskedSsn() throws Exception {
        CreditReport report = new CreditReport("r-42", "***-**-6789", "Avery Patel", Instant.EPOCH, List.of(), 0, 0, 0);
        given(service.generate(eq(Ssn.parse("123-45-6789")), any())).willReturn(report);

        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ssn\":\"123-45-6789\",\"fullName\":\"Avery Patel\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value("r-42"))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Avery"))));
    }

    @Test
    void invalidSsnIsA400ThatDoesNotEchoTheInput() throws Exception {
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"666-12-3456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(Matchers.not(Matchers.containsString("3456"))));
        verifyNoInteractions(service);
    }

    @Test
    void missingSsnIsA400() throws Exception {
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
