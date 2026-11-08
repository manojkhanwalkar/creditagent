package com.creditagent.web.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.Ssn;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KycHttpClientTest {

    /** Starts like a PNG; the provider is mocked, so the rest does not matter. */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};
    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F'};

    final RestClient.Builder builder = RestClient.builder().baseUrl("http://kyc.test");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final KycHttpClient client = new KycHttpClient(builder.build());

    void provider(String json) {
        server.expect(requestTo("http://kyc.test/api/v1/ssn-verifications"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void aVerifiedCardGivesItsSsnAndReference() {
        server.expect(requestTo("http://kyc.test/api/v1/ssn-verifications"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"document\"; filename=\"card.png\"")))
                .andRespond(withSuccess("""
                        {"decision":"VERIFIED","ssn":"123-45-6789","verificationId":"kyc_abc"}
                        """, MediaType.APPLICATION_JSON));

        IdentityVerifier.VerifiedSsn verified = client.verifySsnCard(PNG);

        assertThat(verified.ssn()).isEqualTo(Ssn.parse("123456789"));
        assertThat(verified.verificationId()).isEqualTo("kyc_abc");
        assertThat(verified.toString()).doesNotContain("12345");
        server.verify();
    }

    @Test
    void jpegIsSentAsJpeg() {
        server.expect(requestTo("http://kyc.test/api/v1/ssn-verifications"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"card.jpg\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Content-Type: image/jpeg")))
                .andRespond(withSuccess("""
                        {"decision":"VERIFIED","ssn":"123-45-6789","verificationId":"kyc_abc"}
                        """, MediaType.APPLICATION_JSON));
        client.verifySsnCard(JPEG);
        server.verify();
    }

    @Test
    void aBlurryRejectionKeepsItsReason() {
        provider("""
                {"decision":"REJECTED","reason":"IMAGE_BLURRY","message":"too blurry"}
                """);
        assertThatThrownBy(() -> client.verifySsnCard(PNG))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentRejectedException.IMAGE_BLURRY))
                .hasMessageContaining("blurry")
                .hasMessageContaining("again");
    }

    @Test
    void anUnknownRejectionReasonGetsTheGenericMessage() {
        provider("""
                {"decision":"REJECTED","reason":"SOMETHING_NEW","message":"<b>provider text</b>"}
                """);
        assertThatThrownBy(() -> client.verifySsnCard(PNG))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentRejectedException.DOCUMENT_REJECTED))
                .hasMessageNotContaining("provider text");
    }

    @Test
    void anythingButPngOrJpegNeverReachesTheProvider() {
        assertThatThrownBy(() -> client.verifySsnCard("GIF89a....".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentRejectedException.UNSUPPORTED_IMAGE));
        assertThatThrownBy(() -> client.verifySsnCard(new byte[0])).isInstanceOf(DocumentRejectedException.class);
        server.verify(); // no request was expected or made
    }

    @Test
    void providerFailuresAreServiceUnavailable() {
        server.expect(requestTo("http://kyc.test/api/v1/ssn-verifications")).andRespond(withServerError());
        assertThatThrownBy(() -> client.verifySsnCard(PNG)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void aVerifiedAnswerWithABadSsnIsTheProvidersFault() {
        provider("""
                {"decision":"VERIFIED","ssn":"000-00-0000","verificationId":"kyc_abc"}
                """);
        assertThatThrownBy(() -> client.verifySsnCard(PNG))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining("000-00");
    }

    @Test
    void aVerifiedAnswerWithoutAReferenceIsRefused() {
        provider("""
                {"decision":"VERIFIED","ssn":"123-45-6789"}
                """);
        assertThatThrownBy(() -> client.verifySsnCard(PNG)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void anOversizedImageIsADocumentProblem() {
        server.expect(requestTo("http://kyc.test/api/v1/ssn-verifications"))
                .andRespond(withStatus(HttpStatus.CONTENT_TOO_LARGE));
        assertThatThrownBy(() -> client.verifySsnCard(PNG))
                .isInstanceOfSatisfying(DocumentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo("IMAGE_SIZE"));
    }
}
