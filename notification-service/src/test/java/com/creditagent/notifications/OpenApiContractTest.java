package com.creditagent.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.web.support.InboundLimitFilter;
import com.creditagent.web.support.ServiceToken;
import com.creditagent.web.support.ServiceTokenFilter;
import com.creditagent.web.support.TokenBucket;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Provider contract: the published OpenAPI file parses, documents exactly the routes the controller
 * serves, every status the API can answer, and the same channel and template names as the code.
 */
class OpenApiContractTest extends ApiTestSupport {

    static final String SPEC = "/static/openapi/notification-service-v1.yaml";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @SuppressWarnings("unchecked")
    static Map<String, Object> spec() throws Exception {
        try (InputStream in = OpenApiContractTest.class.getResourceAsStream(SPEC)) {
            assertThat(in).as(SPEC).isNotNull();
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> at(Map<String, Object> node, String... path) {
        Map<String, Object> current = node;
        for (String step : path) {
            current = (Map<String, Object>) current.get(step);
            assertThat(current).as(String.join(".", path)).isNotNull();
        }
        return current;
    }

    @Test
    void theSpecDocumentsExactlyTheControllersRoutes() throws Exception {
        Map<String, Object> spec = spec();
        assertThat(spec.get("openapi")).asString().startsWith("3.");
        Set<String> documented = new TreeSet<>();
        at(spec, "paths").forEach((path, operations) -> ((Map<String, Object>) operations).keySet()
                .forEach(method -> documented.add(method.toUpperCase() + " " + path)));
        Set<String> served = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream().flatMap(pattern -> info.getMethodsCondition().getMethods()
                        .stream().map(method -> method + " " + pattern)))
                .filter(route -> route.contains(" /internal/"))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(served).isEqualTo(documented).containsExactly("POST " + NotificationController.PATH);

        Map<String, Object> post = at(spec, "paths", NotificationController.PATH, "post");
        Map<String, Object> header = ((List<Map<String, Object>>) post.get("parameters")).get(0);
        assertThat(header).containsEntry("name", NotificationController.IDEMPOTENCY_KEY).containsEntry("required", true);
        assertThat(((Map<String, Object>) header.get("schema")).get("maxLength")).isEqualTo(NotificationService.MAX_KEY_LENGTH);
    }

    @Test
    void theSpecNamesTheSameChannelsAndTemplatesAsTheCode() throws Exception {
        Map<String, Object> request = at(spec(), "components", "schemas", "SendRequest", "properties");
        assertThat((List<String>) at(request, "channel").get("enum"))
                .containsExactlyElementsOf(Arrays.stream(Channel.values()).map(Enum::name).toList());
        assertThat((List<String>) at(request, "template").get("enum"))
                .containsExactlyElementsOf(Arrays.stream(Template.values()).map(Enum::name).toList());
        assertThat(at(request, "to").get("maxLength")).isEqualTo(Channel.MAX_TO_LENGTH);
        assertThat(at(request, "params").get("maxProperties")).isEqualTo(Template.MAX_PARAMS);
        assertThat(at(request, "params", "additionalProperties").get("maxLength")).isEqualTo(Template.MAX_PARAM_VALUE_LENGTH);
    }

    @Test
    void everyStatusTheApiAnswersIsDocumentedAndEveryDocumentedStatusHappens() throws Exception {
        Set<String> documented = new TreeSet<>(at(spec(), "paths", NotificationController.PATH, "post", "responses").keySet());
        Set<String> produced = new TreeSet<>();

        produced.add(status(post("c-1", OTP_BODY)));                              // 202
        produced.add(status(post("c-1", OTP_BODY.replace("042917", "000000"))));  // 409
        produced.add(status(post("c-2", "{}")));                                    // 400
        failNext.set(new DeliveryException(Channel.SMS, new RuntimeException(), true));
        produced.add(status(post("c-3", OTP_BODY)));                              // 422
        failNext.set(new DeliveryException(Channel.SMS, SdkClientException.create("timeout"), false));
        produced.add(status(post("c-4", OTP_BODY)));                              // 503
        produced.add(status(post("c-5", OTP_BODY.replace("\"OTP_SIGN_IN\"", "\"OTP_SIGN_IN\",\"pad\":\""
                + "x".repeat((int) NotificationConfiguration.MAX_BODY_BYTES) + "\""))));  // 413
        produced.add(status(mvc.perform(MockMvcRequestBuilders.post(NotificationController.PATH)
                .contentType(MediaType.APPLICATION_JSON).content(OTP_BODY))));     // 401

        MockMvc tokenOff = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ServiceTokenFilter(new ServiceToken(""))).build();
        produced.add(status(tokenOff.perform(MockMvcRequestBuilders.post(NotificationController.PATH)
                .contentType(MediaType.APPLICATION_JSON).content(OTP_BODY))));     // 404

        MockMvc throttled = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new InboundLimitFilter(new TokenBucket(1, 0.001), 0)).build();
        var request = MockMvcRequestBuilders.post(NotificationController.PATH).header("Authorization", "Bearer " + TOKEN)
                .header(NotificationController.IDEMPOTENCY_KEY, "c-6").contentType(MediaType.APPLICATION_JSON).content(OTP_BODY);
        throttled.perform(request);
        produced.add(status(throttled.perform(request)));                          // 429

        assertThat(produced).isEqualTo(documented);
    }

    private static String status(org.springframework.test.web.servlet.ResultActions result) {
        return Integer.toString(result.andReturn().getResponse().getStatus());
    }
}
