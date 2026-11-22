package com.creditagent.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Consumer contract: the request {@link RemoteEmailSender} builds fits the notification-service's
 * published schema ({@code contracts/notification-service-v1.yaml}, copied from that service): the
 * required fields and no others, the enum values, DARK_WEB_ALERT's param names, and the key format.
 */
class NotificationContractTest {

    static Map<String, Object> spec() throws Exception {
        try (InputStream in = NotificationContractTest.class.getResourceAsStream("/contracts/notification-service-v1.yaml")) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T at(Object node, String... path) {
        Object here = node;
        for (String step : path) {
            here = ((Map<String, Object>) here).get(step);
        }
        return (T) here;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAlertRequestMatchesTheSendRequestSchema() throws Exception {
        Map<String, Object> schema = at(spec(), "components", "schemas", "SendRequest");
        Map<String, Object> properties = at(schema, "properties");
        Map<String, Object> body = RemoteEmailSender.body("ada@example.com", RemoteEmailSenderTest.ALERTS, "https://credit.example");

        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(at(schema, "required"));
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) at(properties, "channel", "enum")).contains((String) body.get("channel"));
        assertThat((List<String>) at(properties, "template", "enum")).contains((String) body.get("template"));

        Map<String, String> params = (Map<String, String>) body.get("params");
        assertThat(params.size()).isLessThanOrEqualTo(at(properties, "params", "maxProperties"));
        int nameMax = at(properties, "params", "propertyNames", "maxLength");
        int valueMax = at(properties, "params", "additionalProperties", "maxLength");
        assertThat(params).allSatisfy((name, value) -> {
            assertThat(name.length()).isLessThanOrEqualTo(nameMax);
            assertThat(value.length()).isLessThanOrEqualTo(valueMax);
        });

        String description = at(properties, "template", "description");
        String alert = description.substring(description.indexOf("DARK_WEB_ALERT"));
        assertThat(alert).contains("EMAIL only", "count (", "firstSeen (ISO date)", "optional", "appUrl");
        assertThat(params.keySet()).containsExactly("count", "firstSeen", "appUrl");
    }

    @Test
    void thePathHeadersAndKeyFormatMatch() throws Exception {
        Map<String, Object> post = at(spec(), "paths", RemoteEmailSender.PATH, "post");
        List<Map<String, Object>> parameters = at(post, "parameters");
        Map<String, Object> key = parameters.stream().filter(p -> RemoteEmailSender.IDEMPOTENCY_KEY.equals(p.get("name")))
                .findFirst().orElseThrow();
        assertThat(key).containsEntry("in", "header").containsEntry("required", true);
        String sample = RemoteEmailSender.create("http://x", Duration.ofSeconds(1), RemoteEmailSenderTest.TOKEN)
                .idempotencyKey("ada@example.com", RemoteEmailSenderTest.RUN);
        assertThat(Pattern.compile(at(key, "schema", "pattern")).matcher(sample).matches()).isTrue();
        assertThat((String) at(spec(), "components", "securitySchemes", "serviceToken", "scheme")).isEqualTo("bearer");
    }

    /** Every 202 status is one the sender treats as success (it accepts any 2xx). */
    @Test
    @SuppressWarnings("unchecked")
    void every202StatusIsKnownSentOrUncertain() throws Exception {
        assertThat((List<String>) at(spec(), "components", "schemas", "SendResponse", "properties", "status", "enum"))
                .containsExactlyInAnyOrder("SENT", "UNCERTAIN");
        assertThat((String) at(spec(), "paths", RemoteEmailSender.PATH, "post", "responses", "202", "content",
                "application/json", "schema", "$ref")).isEqualTo("#/components/schemas/SendResponse");
    }
}
