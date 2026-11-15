package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** The erase API on a real server, so the service-token filter registered for /internal/* applies. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportEraseApiTest {

    static final String TOKEN = "service-token-service-token-service-token";
    static final Ssn ADA = Ssn.parse("123-45-6789");

    @TestConfiguration
    static class Store {
        @Bean
        @Primary
        CreditReportStore inMemoryReports() {
            return new InMemoryCreditReportStore();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("credit.reports.ssn-hash-key", () -> "generator-test-key-0123456789abcdef0123456789");
        registry.add("credit.reports.encryption-key", () -> "generator-enc-key-0123456789abcdef0123456789");
        registry.add("credit.generator.service-token", () -> TOKEN);
    }

    @LocalServerPort
    int port;

    @Autowired
    CreditReportStore store;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        store.replaceAll(ADA, Map.of(Bureau.EQUIFAX, "{}", Bureau.EXPERIAN, "{}"));
    }

    HttpStatusCode erase(String token, String body) {
        var request = client.post().uri(ReportEraseController.PATH).contentType(MediaType.APPLICATION_JSON).body(body);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, res) -> res.getStatusCode());
    }

    @Test
    void withTheTokenEveryReportIsErasedAndRepeatingIsHarmless() {
        assertThat(erase(TOKEN, "{\"ssn\":\"123-45-6789\"}").value()).isEqualTo(204);
        assertThat(store.findAll(ADA)).isEmpty();
        assertThat(erase(TOKEN, "{\"ssn\":\"123-45-6789\"}").value()).isEqualTo(204);
    }

    @Test
    void withoutOrWithAWrongTokenNothingIsErased() {
        assertThat(erase(null, "{\"ssn\":\"123-45-6789\"}").value()).isEqualTo(401);
        assertThat(erase("wrong-token-wrong-token-wrong-token-wrong", "{\"ssn\":\"123-45-6789\"}").value()).isEqualTo(401);
        assertThat(store.findAll(ADA)).hasSize(2);
    }

    @Test
    void anInvalidSsnIsA400() {
        assertThat(erase(TOKEN, "{\"ssn\":\"666-12-3456\"}").value()).isEqualTo(400);
        assertThat(erase(TOKEN, "{\"ssn\":\"\"}").value()).isEqualTo(400);
    }

    @Test
    void thePublicReportApiDoesNotNeedTheToken() {
        int status = client.post().uri("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .body("{\"ssn\":\"234-56-7890\"}").exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(201);
    }
}
