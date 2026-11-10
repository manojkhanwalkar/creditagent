package com.creditagent.alerts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/v1/alerts/lookup} with {@code {email}}: whether the dark web monitor has found
 * this address, and where and when. Internal only (service token, see {@code ServiceTokenFilter}). The
 * address travels in the body, never in a URL, and is not echoed back.
 */
@RestController
class AlertsController {

    static final String PATH = "/internal/v1/alerts/lookup";

    private final AlertRepository alerts;

    AlertsController(AlertRepository alerts) {
        this.alerts = alerts;
    }

    record LookupRequest(@NotBlank @Email @Size(max = 254) String email) {
        @Override
        public String toString() {
            return "LookupRequest[email=<redacted>]";
        }
    }

    record Finding(String location, Instant foundAt) {}

    record LookupResponse(boolean exposed, List<Finding> findings) {}

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    LookupResponse lookup(@Valid @RequestBody LookupRequest request) {
        List<Finding> findings = alerts.find(request.email()).stream()
                .map(alert -> new Finding(alert.location(), alert.timestamp()))
                .toList();
        return new LookupResponse(!findings.isEmpty(), findings);
    }
}
