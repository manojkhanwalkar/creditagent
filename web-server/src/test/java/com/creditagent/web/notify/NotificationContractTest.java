package com.creditagent.web.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.notify.NotificationGateway.Notification;
import com.creditagent.web.notify.NotificationGateway.Template;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Consumer contract: the request {@link RemoteNotificationGateway} builds fits the notification-service's
 * published schema ({@code contracts/notification-service-v1.yaml}, copied from that service): the
 * required fields and no others, the enum values, the param names of the template, and the key format.
 */
class NotificationContractTest {

    @SuppressWarnings("unchecked")
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
    void theOtpRequestMatchesTheSendRequestSchema() throws Exception {
        Map<String, Object> spec = spec();
        Map<String, Object> schema = at(spec, "components", "schemas", "SendRequest");
        Map<String, Object> properties = at(schema, "properties");

        for (OtpChannel channel : OtpChannel.values()) {
            Map<String, Object> body = RemoteNotificationGateway.body(new Notification(channel,
                    channel == OtpChannel.EMAIL ? "a@example.com" : "+15555550100", Template.OTP_SIGN_IN,
                    Map.of("code", "042917", "validSeconds", "300"), "otp:challenge-1:1"));

            assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(at(schema, "required"));
            assertThat(properties.keySet()).containsAll(body.keySet());
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat((List<String>) at(properties, "channel", "enum")).contains((String) body.get("channel"));
            assertThat((List<String>) at(properties, "template", "enum")).contains((String) body.get("template"));
            assertThat(((String) body.get("to")).length()).isLessThanOrEqualTo(at(properties, "to", "maxLength"));

            Map<String, String> params = (Map<String, String>) body.get("params");
            assertThat(params.size()).isLessThanOrEqualTo(at(properties, "params", "maxProperties"));
            int nameMax = at(properties, "params", "propertyNames", "maxLength");
            int valueMax = at(properties, "params", "additionalProperties", "maxLength");
            assertThat(params).allSatisfy((name, value) -> {
                assertThat(name.length()).isLessThanOrEqualTo(nameMax);
                assertThat(value.length()).isLessThanOrEqualTo(valueMax);
            });
        }
    }

    @Test
    void theParamNamesAreTheOnesTheTemplateTakes() throws Exception {
        String description = at(spec(), "components", "schemas", "SendRequest", "properties", "template", "description");
        String otp = description.substring(description.indexOf("OTP_SIGN_IN"), description.indexOf("DARK_WEB_ALERT"));
        assertThat(otp).contains("code (6 digits)", "validSeconds (1 to 86400)");
        assertThat(Template.values()).extracting(Enum::name)
                .containsExactlyInAnyOrderElementsOf(at(spec(), "components", "schemas", "SendRequest", "properties",
                        "template", "enum"));
    }

    @Test
    void thePathMethodHeadersAndKeyFormatMatch() throws Exception {
        Map<String, Object> post = at(spec(), "paths", RemoteNotificationGateway.PATH, "post");
        assertThat(post).isNotNull();
        List<Map<String, Object>> parameters = at(post, "parameters");
        Map<String, Object> key = parameters.stream().filter(p -> RemoteNotificationGateway.IDEMPOTENCY_KEY.equals(p.get("name")))
                .findFirst().orElseThrow();
        assertThat(key).containsEntry("in", "header").containsEntry("required", true);
        Pattern format = Pattern.compile(at(key, "schema", "pattern"));
        assertThat(format.matcher("otp:Zm9vYmFyYmF6cXV4LXNhbXBsZS1jaGFsbGVuZ2UtaWQtNDM:3").matches()).isTrue();
        assertThat((Map<String, Object>) at(spec(), "components", "securitySchemes", "serviceToken"))
                .containsEntry("scheme", "bearer");
        assertThat(((Map<String, Object>) post.get("responses")).keySet())
                .contains("202", "400", "401", "409", "422", "429", "503");
    }
}
