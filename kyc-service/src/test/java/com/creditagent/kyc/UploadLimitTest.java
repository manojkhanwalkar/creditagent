package com.creditagent.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Oversized uploads against a real Tomcat: MockMvc does not apply the multipart limits, and the
 * limit is hit while the request is parsed, before any controller is chosen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "kyc.max-image-bytes=4096",
    "spring.servlet.multipart.max-file-size=4096",
    "spring.servlet.multipart.max-request-size=1MB"
})
class UploadLimitTest {

    static final String BOUNDARY = "limit-test-boundary";

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    static byte[] multipart(int imageBytes) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"document\"; filename=\"card.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(new byte[imageBytes]);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    HttpResponse<String> post(BodyPublisher body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ssn-verifications"))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(body)
                        .build(),
                BodyHandlers.ofString());
    }

    @Test
    void aChunkedFileOverTheMultipartLimitIs413() throws Exception {
        byte[] body = multipart(20_000); // over max-file-size, under the request filter's cap
        HttpResponse<String> response = post(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void aDeclaredLengthOverTheRequestCapIs413() throws Exception {
        HttpResponse<String> response = post(BodyPublishers.ofByteArray(multipart(200_000)));
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void aSmallUploadReachesTheController() throws Exception {
        HttpResponse<String> response = post(BodyPublishers.ofByteArray(multipart(100)));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UNSUPPORTED_IMAGE");
    }
}
