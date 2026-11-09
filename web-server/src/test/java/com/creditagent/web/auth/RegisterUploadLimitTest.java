package com.creditagent.web.auth;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A card photo over the multipart limit is a 413 problem detail, which the UI turns into "the photo
 * must be 5 MB or smaller". Needs a real Tomcat: MockMvc does not apply multipart limits, and the
 * limit is hit while the body is parsed, before any controller (or its exception handlers) is chosen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.servlet.multipart.max-file-size=4096",
    "spring.servlet.multipart.max-request-size=1MB",
    "credit.kyc.url=http://kyc.invalid",
    "credit.auth.dynamodb.endpoint=http://dynamodb.invalid",
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid"
})
class RegisterUploadLimitTest {

    static final String BOUNDARY = "limit-test-boundary";

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("credit.auth.jwt-secret", () -> TestSecrets.of("jwt"));
        registry.add("credit.auth.ssn-hmac-key", () -> TestSecrets.of("ssn"));
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    static byte[] form(int photoBytes) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String[] field : new String[][] {
            {"userId", "avery"}, {"password", "correct horse battery staple"},
            {"email", "avery@example.com"}, {"phone", "+14155550100"}}) {
            body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n"
                    + field[1] + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"ssnCard\"; filename=\"card.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(new byte[photoBytes]);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    HttpResponse<String> register(BodyPublisher body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .header("Accept", "application/json")
                        .POST(body)
                        .build(),
                BodyHandlers.ofString());
    }

    @Test
    void aPhotoOverTheLimitIs413() throws Exception {
        HttpResponse<String> response = register(BodyPublishers.ofByteArray(form(20_000)));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"status\":413");
    }

    @Test
    void aChunkedPhotoOverTheLimitIs413() throws Exception {
        byte[] body = form(20_000);
        HttpResponse<String> response = register(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void anOversizedJsonBodyIs413BeforeItIsRead() throws Exception {
        String huge = "{\"userId\":\"avery\",\"password\":\"" + "x".repeat(70_000) + "\"}";
        for (String path : new String[] {"/api/v1/auth/login", "/api/v1/credit-score"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Content-Type", "application/json")
                            .POST(BodyPublishers.ofString(huge))
                            .build(),
                    BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(413);
        }
    }
}
