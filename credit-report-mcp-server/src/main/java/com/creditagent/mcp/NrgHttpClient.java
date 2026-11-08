package com.creditagent.mcp;

import com.creditagent.common.bureau.Bureau;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls {@code POST /api/v1/normalized-reports} on the NRG service. */
class NrgHttpClient implements ReportNormalizerClient {

    static final String PATH = "/api/v1/normalized-reports";

    private final RestClient restClient;
    /** Strict: a file with anything after its JSON value is malformed, not silently truncated. */
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    NrgHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    @Override
    public String normalize(Map<Bureau, String> bureauReports) {
        ObjectNode body = mapper.createObjectNode();
        bureauReports.forEach((bureau, json) -> {
            try {
                // Parsed rather than spliced in, so a malformed file cannot change the envelope.
                body.set(bureau.name().toLowerCase(Locale.ROOT), mapper.readTree(json));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(bureau.directoryName() + " credit file is not valid JSON");
            }
        });
        try {
            String response = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("NRG returned no body");
            }
            return response;
        } catch (RestClientException e) {
            throw new IllegalStateException("NRG request failed", e);
        }
    }
}
