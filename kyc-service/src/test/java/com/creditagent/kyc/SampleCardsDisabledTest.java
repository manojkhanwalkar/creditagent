package com.creditagent.kyc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The shipped defaults (application.yml): sample cards are off. */
@WebMvcTest(KycController.class)
@Import(KycConfiguration.class)
class SampleCardsDisabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sampleCardsAreOffByDefault() throws Exception {
        mvc.perform(get("/api/v1/sample-cards/123-45-6789")).andExpect(status().isNotFound());
    }
}
