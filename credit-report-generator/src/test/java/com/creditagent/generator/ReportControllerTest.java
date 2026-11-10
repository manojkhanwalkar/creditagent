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
import com.creditagent.common.bureau.Bureau;
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
    void createsReportsAndReturnsOnlyIdMaskedSsnAndBureaus() throws Exception {
        CreditReport report = new CreditReport("truth-id", "***-**-6789", "Avery Patel", Instant.EPOCH, List.of(), 0, 0, 0);
        given(service.generate(eq(Ssn.parse("123-45-6789")), any()))
                .willReturn(new CreditReportService.GeneratedReports("r-42", report, List.of(Bureau.EQUIFAX, Bureau.TRANSUNION)));

        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ssn\":\"123-45-6789\",\"fullName\":\"Avery Patel\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value("r-42"))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"))
                .andExpect(jsonPath("$.bureaus[0]").value("Equifax"))
                .andExpect(jsonPath("$.bureaus[1]").value("TransUnion"))
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
    void aStoreOutageIsA503WithRetryAfter() throws Exception {
        given(service.generate(any(), any())).willThrow(software.amazon.awssdk.core.exception.SdkClientException.create("down"));
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("6789"))));
    }

    @Test
    void missingSsnIsA400() throws Exception {
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
