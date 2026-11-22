package com.creditagent.notifications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.creditagent.web.support.InboundLimitFilter;
import com.creditagent.web.support.RequestSizeLimitFilter;
import com.creditagent.web.support.ServiceTokenFilter;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The real controller and the real filters in front of it (MockMvc does not apply filter registrations,
 * so they are added here as the registrations order them). The service is a mock that, by default,
 * calls a real {@link NotificationService} over an in-memory store and a scriptable sender.
 */
@SpringBootTest(properties = {
    "credit.notifications.service-token=" + ApiTestSupport.TOKEN,
    "credit.notifications.dynamodb-endpoint=http://localhost:1",
    "credit.notifications.email=log",
    "credit.notifications.sms=log"
})
abstract class ApiTestSupport {

    static final String TOKEN = "service-token-service-token-service-token";
    static final String OTP_BODY =
            "{\"channel\":\"SMS\",\"to\":\"+15555550100\",\"template\":\"OTP_SIGN_IN\",\"params\":{\"code\":\"042917\",\"validSeconds\":\"300\"}}";

    @Autowired
    WebApplicationContext context;

    @Autowired
    FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilter;

    @Autowired
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter;

    @Autowired
    FilterRegistrationBean<InboundLimitFilter> inboundLimitFilter;

    @MockitoBean
    NotificationService service;

    MockMvc mvc;
    final List<String> sent = new ArrayList<>();
    final AtomicReference<RuntimeException> failNext = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        Sender sender = (to, message) -> {
            RuntimeException failure = failNext.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            sent.add(message.text());
            return "p-" + sent.size();
        };
        NotificationService real = new NotificationService(new InMemoryNotificationStore(),
                Map.of(Channel.EMAIL, sender, Channel.SMS, sender), TOKEN, Clock.systemUTC(), Duration.ofSeconds(60),
                Duration.ofDays(30));
        given(service.send(any(), any(), any(), any(), any())).willAnswer(call -> real.send(call.getArgument(0),
                call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(serviceTokenFilter.getFilter(), inboundLimitFilter.getFilter())
                .addFilter(requestSizeLimitFilter.getFilter(), NotificationController.PATH)
                .build();
    }

    ResultActions post(String key, String body) throws Exception {
        var request = MockMvcRequestBuilders.post(NotificationController.PATH)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header(NotificationController.IDEMPOTENCY_KEY, key);
        }
        return mvc.perform(request);
    }
}
