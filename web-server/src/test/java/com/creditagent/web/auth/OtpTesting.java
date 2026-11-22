package com.creditagent.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.creditagent.common.AuditLog;
import com.creditagent.web.auth.otp.OtpChannel;
import com.creditagent.web.auth.otp.OtpSender;
import com.creditagent.web.notify.LocalNotificationGateway;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Builds the OTP login service for tests and walks the three login steps through MockMvc. */
public final class OtpTesting {

    public static final OtpLoginService.Limits LIMITS = new OtpLoginService.Limits(
            Duration.ofMinutes(5), Duration.ofMinutes(10), 5, 3, Duration.ofSeconds(30), 10);

    private OtpTesting() {}

    public static OtpLoginService service(AuthService auth, OtpChallengeRepository challenges, OtpSender sender,
            Clock clock, AuditLog audit) {
        return new OtpLoginService(auth, challenges,
                new LocalNotificationGateway(Map.of(OtpChannel.EMAIL, sender, OtpChannel.SMS, sender)), LIMITS,
                TestSecrets.of("otp").getBytes(java.nio.charset.StandardCharsets.UTF_8), clock, audit);
    }

    public static OtpLoginService service(AuthService auth, OtpSender sender, Clock clock) {
        return service(auth, new InMemoryRepositories.OtpChallenges(), sender, clock, new AuditLog());
    }

    /**
     * Runs the password step and sends a code by {@code channel}; returns the verify request with the
     * code that was "delivered". If the password step fails, returns that step again so the caller can
     * assert on its answer.
     */
    public static MockHttpServletRequestBuilder loginRequest(MockMvc mvc, RecordingOtpSender sender, String userId,
            String password, OtpChannel channel) throws Exception {
        MockHttpServletRequestBuilder passwordStep = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"password\":\"" + password + "\"}");
        MvcResult first = mvc.perform(passwordStep).andReturn();
        if (first.getResponse().getStatus() != 200) {
            return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + userId + "\",\"password\":\"" + password + "\"}");
        }
        String challengeId = JsonPath.read(first.getResponse().getContentAsString(), "$.challengeId");
        MvcResult sent = mvc.perform(post("/api/v1/auth/login/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"" + challengeId + "\",\"channel\":\"" + channel + "\"}")).andReturn();
        if (sent.getResponse().getStatus() != 202) {
            throw new IllegalStateException("Code not sent: " + sent.getResponse().getContentAsString());
        }
        return post("/api/v1/auth/login/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + sender.lastCode() + "\"}");
    }

    public static MockHttpServletRequestBuilder loginRequest(MockMvc mvc, RecordingOtpSender sender, String userId,
            String password) throws Exception {
        return loginRequest(mvc, sender, userId, password, OtpChannel.EMAIL);
    }
}
