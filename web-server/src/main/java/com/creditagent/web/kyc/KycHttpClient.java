package com.creditagent.web.kyc;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import java.util.Objects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends the card image to the KYC provider ({@code kyc-service}, a stand-in for an external one) as
 * {@code multipart/form-data} and maps its answer. Only PNG and JPEG files are sent: anything else is
 * refused here, so arbitrary uploads never reach a third party. The user's file name is not sent.
 */
public class KycHttpClient implements IdentityVerifier {

    static final String PATH = "/api/v1/ssn-verifications";

    /** The provider's response body. */
    record ProviderResponse(String decision, String reason, String message, String ssn, String verificationId) {
        @Override
        public String toString() {
            return "ProviderResponse[decision=" + decision + ", reason=" + reason + ", ssn=<redacted>]";
        }
    }

    private final RestClient restClient;

    public KycHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    @Override
    public VerifiedSsn verifySsnCard(byte[] image) {
        MediaType type = imageType(image);
        if (type == null) {
            throw new DocumentRejectedException(DocumentRejectedException.UNSUPPORTED_IMAGE);
        }
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("document", new NamedResource(image, MediaType.IMAGE_PNG.equals(type) ? "card.png" : "card.jpg"))
                .contentType(type);
        ProviderResponse response;
        try {
            response = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(parts.build())
                    .retrieve()
                    .body(ProviderResponse.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.CONTENT_TOO_LARGE)) {
                throw new DocumentRejectedException("IMAGE_SIZE", e);
            }
            throw new ServiceUnavailableException("KYC provider refused the request: " + e.getStatusCode().value(), e);
        } catch (RestClientException | HttpMessageConversionException | IllegalArgumentException e) {
            throw new ServiceUnavailableException("KYC provider request failed", e);
        }
        return verified(response);
    }

    private static VerifiedSsn verified(ProviderResponse response) {
        if (response == null || response.decision() == null) {
            throw new ServiceUnavailableException("KYC provider returned no decision", null);
        }
        switch (response.decision()) {
            case "VERIFIED" -> {
                if (response.verificationId() == null || response.verificationId().isBlank()) {
                    throw new ServiceUnavailableException("KYC provider verified without a reference", null);
                }
                try {
                    return new VerifiedSsn(Ssn.parse(response.ssn()), response.verificationId());
                } catch (InvalidSsnException e) {
                    // The provider said yes but sent no usable SSN: its fault, not the user's.
                    throw new ServiceUnavailableException("KYC provider returned an invalid SSN", null);
                }
            }
            case "REJECTED" -> throw new DocumentRejectedException(response.reason());
            default -> throw new ServiceUnavailableException("KYC provider returned an unknown decision", null);
        }
    }

    /** PNG or JPEG by magic bytes, or null. */
    static MediaType imageType(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return MediaType.IMAGE_PNG;
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return MediaType.IMAGE_JPEG;
        }
        return null;
    }

    /** A byte array part with a file name, so it is sent as a file. */
    private static final class NamedResource extends ByteArrayResource {
        private final String filename;

        NamedResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
