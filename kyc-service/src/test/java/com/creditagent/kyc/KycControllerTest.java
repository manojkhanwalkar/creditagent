package com.creditagent.kyc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.common.Ssn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KycController.class)
@Import(KycConfiguration.class)
@TestPropertySource(properties = {
    "kyc.min-sharpness=100",
    "kyc.max-image-bytes=10485760",
    "kyc.max-pixels=40000000",
    "kyc.min-width=600",
    "kyc.min-height=360",
    "kyc.sample-cards-enabled=true"
})
class KycControllerTest {

    @Autowired
    MockMvc mvc;

    static MockMultipartFile card(int blur) {
        return new MockMultipartFile("document", "card.png", MediaType.IMAGE_PNG_VALUE,
                SsnCardRenderer.render(Ssn.parse("234-56-7890"), blur));
    }

    @Test
    void aSharpCardIsVerified() throws Exception {
        mvc.perform(multipart("/api/v1/ssn-verifications").file(card(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("VERIFIED"))
                .andExpect(jsonPath("$.ssn").value("234-56-7890"))
                .andExpect(jsonPath("$.verificationId").isString())
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void aBlurredCardIsRejectedWithAReason() throws Exception {
        mvc.perform(multipart("/api/v1/ssn-verifications").file(card(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("IMAGE_BLURRY"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.ssn").doesNotExist());
    }

    @Test
    void aRequestWithoutTheDocumentPartIs400() throws Exception {
        mvc.perform(multipart("/api/v1/ssn-verifications")
                        .file(new MockMultipartFile("other", "x.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sampleCardsArePngsAndCheckTheirInput() throws Exception {
        mvc.perform(get("/api/v1/sample-cards/123-45-6789").param("blur", "4"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(get("/api/v1/sample-cards/000-00-0000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/sample-cards/123-45-6789").param("blur", "99")).andExpect(status().isBadRequest());
    }
}
