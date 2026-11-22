package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import software.amazon.awssdk.core.exception.SdkClientException;

/** Through the service-token filter and the size limit to the controller: every answer the API gives. */
class NotificationApiTest extends ApiTestSupport {

    @Test
    void withoutTheServiceTokenNothingIsSent() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(NotificationController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .header(NotificationController.IDEMPOTENCY_KEY, "k").content(OTP_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(MockMvcRequestBuilders.post(NotificationController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer wrong-token-wrong-token-wrong-token")
                        .header(NotificationController.IDEMPOTENCY_KEY, "k").content(OTP_BODY))
                .andExpect(status().isUnauthorized());
        assertThat(sent).isEmpty();
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void aSendIs202AndARepeatAnswersTheSameIdWithoutSendingAgain() throws Exception {
        String first = post("otp:c1:1", OTP_BODY).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("042917"))))
                .andReturn().getResponse().getContentAsString();
        String again = post("otp:c1:1", OTP_BODY).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(again).isEqualTo(first);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isEqualTo(TemplateTest.TODAY_OTP_SMS);
    }

    @Test
    void theSameKeyForADifferentRequestIs409() throws Exception {
        post("k-409", OTP_BODY).andExpect(status().isAccepted());
        post("k-409", OTP_BODY.replace("042917", "111111")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void aProviderRefusalIs422AndFreesTheKey() throws Exception {
        failNext.set(new DeliveryException(Channel.SMS, new RuntimeException(), true));
        post("k-422", OTP_BODY).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.refused").value(true));
        post("k-422", OTP_BODY).andExpect(status().isAccepted());
    }

    @Test
    void anUnknownOutcomeOrAStoreOutageIs503WithRetryAfter() throws Exception {
        failNext.set(new DeliveryException(Channel.SMS, SdkClientException.create("timeout"), false));
        String id = post("k-503", OTP_BODY).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.refused").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(id).doesNotContain("042917");
        // The message may have arrived: a retry with the same key is answered, not sent again.
        post("k-503", OTP_BODY).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("UNCERTAIN"));
        assertThat(sent).isEmpty();
        org.mockito.BDDMockito.willThrow(SdkClientException.create("dynamodb down"))
                .willThrow(new NotificationService.InProgressException(7))
                .given(service).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        post("k-store", OTP_BODY).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"));
        post("k-store", OTP_BODY).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"channel\":\"FAX\",\"to\":\"+15555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\"}}",
        "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"FREE_TEXT\",\"params\":{\"text\":\"hi\"}}",
        "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\"},\"text\":\"x\"}",
        "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"04291\",\"validSeconds\":\"300\"}}",
        "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\"}}",
        "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\",\"x\":\"1\"}}",
        "{\"channel\":\"SMS\",\"to\":\"5555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\"}}",
        "{\"channel\":\"EMAIL\",\"to\":\"not-an-email\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\"}}",
        "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"DARK_WEB_ALERT\",\"params\":{\"count\":\"1\",\"firstSeen\":\"2026-11-09\"}}",
        "{\"channel\":\"SMS\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\"}}",
        "not json"
    })
    void everyInvalidRequestIs400ProblemDetailThatEchoesNoValue(String body) throws Exception {
        post("k-400", body).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("5555550100"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("04291"))));
        assertThat(sent).isEmpty();
    }

    @Test
    void theIdempotencyKeyIsRequiredAndBounded() throws Exception {
        post(null, OTP_BODY).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString("Idempotency-Key")));
        post("k".repeat(129), OTP_BODY).andExpect(status().isBadRequest());
        assertThat(sent).isEmpty();
    }

    @Test
    void numbersInParamsAreReadAsTheirText() throws Exception {
        post("k-num", "{\"channel\":\"EMAIL\",\"to\":\"ada@example.com\",\"template\":\"DARK_WEB_ALERT\","
                + "\"params\":{\"count\":2,\"firstSeen\":\"2026-11-09\",\"appUrl\":\"https://app.creditagent.example\"}}")
                .andExpect(status().isAccepted());
        assertThat(sent).containsExactly(TemplateTest.TODAY_ALERT);
    }

    @Test
    void aBodyOverTheLimitIs413() throws Exception {
        String huge = OTP_BODY.replace("\"OTP_SIGN_IN\"", "\"OTP_SIGN_IN\",\"pad\":\""
                + "x".repeat((int) NotificationConfiguration.MAX_BODY_BYTES) + "\"");
        post("k-413", huge).andExpect(status().isPayloadTooLarge());
    }
}
