package com.creditagent.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.CreditAdvice;
import com.creditagent.common.Recommendation;
import com.creditagent.common.ScoreBand;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdviceController.class)
class AdviceControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditAdviceClient client;

    @Test
    void returnsTheAgentsAdvice() throws Exception {
        given(client.adviceFor(any())).willReturn(new CreditAdvice(
                "***-**-6789", 702, ScoreBand.GREEN, "ok",
                List.of(new Recommendation(1, "Keep it up", "d", Recommendation.Impact.LOW)), "rules"));

        mvc.perform(post("/api/v1/advice").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(702))
                .andExpect(jsonPath("$.recommendations[0].title").value("Keep it up"));
    }

    @Test
    void invalidSsnIs400() throws Exception {
        mvc.perform(post("/api/v1/advice").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"12\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test
    void agentRejectionIs422() throws Exception {
        given(client.adviceFor(any())).willThrow(new CreditAdviceClient.AgentRejectedException("No credit report"));
        mvc.perform(post("/api/v1/advice").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("No credit report"));
    }

    @Test
    void agentOutageIs502() throws Exception {
        given(client.adviceFor(any()))
                .willThrow(new CreditAdviceClient.AgentCallException("down", new RuntimeException()));
        mvc.perform(post("/api/v1/advice").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isBadGateway());
    }
}
