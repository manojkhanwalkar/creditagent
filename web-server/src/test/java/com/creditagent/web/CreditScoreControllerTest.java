package com.creditagent.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Ssn;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CreditScoreController.class)
@Import(CreditScoreControllerTest.Props.class)
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class CreditScoreControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Props {}

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditScoreService service;

    @Test
    void returnsScoreBandAndMaskedSsn() throws Exception {
        given(service.scoreFor(Ssn.parse("123-45-6789"))).willReturn(CreditScore.of(689));

        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(689))
                .andExpect(jsonPath("$.maxScore").value(850))
                .andExpect(jsonPath("$.band").value("ORANGE"))
                .andExpect(jsonPath("$.ssnMasked").value("***-**-6789"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("123-45"))))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void invalidSsnIs400WithoutCallingDownstream() throws Exception {
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"000-12-3456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("SSN area number is not valid"));
        verifyNoInteractions(service);
    }

    @Test
    void blankSsnIs400() throws Exception {
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ssnInTheQueryStringIsNotAccepted() throws Exception {
        mvc.perform(get("/api/v1/credit-score").param("ssn", "123-45-6789"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownSsnIs404() throws Exception {
        given(service.scoreFor(any())).willThrow(new CreditScoreService.CreditReportNotFoundException());
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downstreamOutageIs503WithAGenericMessage() throws Exception {
        given(service.scoreFor(any()))
                .willThrow(new ServiceUnavailableException("MCP failed for credit-report://ssn/123456789", null));
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(Matchers.not(Matchers.containsString("123456789"))));
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        given(service.scoreFor(any())).willReturn(CreditScore.of(720));
        mvc.perform(post("/api/v1/credit-score").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
