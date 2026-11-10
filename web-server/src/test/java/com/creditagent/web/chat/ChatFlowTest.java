package com.creditagent.web.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.web.WebServerProperties;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.FakeIdentityVerifier;
import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.JwtService;
import com.creditagent.web.auth.SsnHasher;
import com.creditagent.web.auth.TestSecrets;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The chat through the real filter chain: login, own SSN only, report fetch, the agent, logout. */
@WebMvcTest(controllers = ChatController.class)
@Import({ChatFlowTest.Wiring.class, com.creditagent.web.auth.AuthControllerConfig.class})
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class ChatFlowTest {

    static final String PASSWORD = "correct horse battery staple";
    static final String SSN = "123-45-6789";
    static final CreditReport REPORT = new CreditReport("r-1", "***-**-6789", "Avery Doe", Instant.EPOCH, List.of(), 2, 1, 0);

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.MutableClock CLOCK =
                new InMemoryRepositories.MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();

        @Bean
        AuthService authService() {
            return new AuthService(USERS, new InMemoryRepositories.Sessions(), new BCryptPasswordEncoder(4),
                    new SsnHasher(TestSecrets.of("ssn")), new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), CLOCK),
                    Duration.ofMinutes(5), CLOCK);
        }

        /** Runs the logout-time forget at once, so tests can check it. */
        @Bean
        ChatService chatService(AuthService auth, CreditReportSource reports, AdvisorClient advisor) {
            return new ChatService(auth, reports, advisor, Runnable::run);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthService auth;

    @MockitoBean
    CreditReportSource reports;

    @MockitoBean
    AdvisorClient advisor;

    @MockitoBean
    com.creditagent.web.CreditScoreService scores;

    String bearer;

    @BeforeEach
    void signIn() throws Exception {
        if (Wiring.USERS.find("avery").isEmpty()) {
            auth.register("avery", PASSWORD, "avery@example.com", "+1 415 555 0100", FakeIdentityVerifier.card(SSN));
        }
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"avery\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        bearer = "Bearer " + JsonPath.read(login, "$.token");
        given(reports.findReport(Ssn.parse(SSN))).willReturn(Optional.of(REPORT));
        given(advisor.chat(anyString(), anyString(), any()))
                .willReturn(new AdvisorClient.ChatReply("Your card balances look fine.", "Educational only.", false));
    }

    MockHttpServletRequestBuilder chat(String ssn, String message) {
        return post(ChatController.PATH).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ssn\":\"" + ssn + "\",\"message\":\"" + message + "\"}");
    }

    @Test
    void aQuestionAboutTheUsersOwnReportIsAnswered() throws Exception {
        mvc.perform(chat(SSN, "What is my utilization?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Your card balances look fine."))
                .andExpect(jsonPath("$.disclaimer").value("Educational only."))
                .andExpect(jsonPath("$.refused").value(false));
        ArgumentCaptor<String> conversation = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<CreditReport> sent = ArgumentCaptor.forClass(CreditReport.class);
        verify(advisor).chat(conversation.capture(), eq("What is my utilization?"), sent.capture());
        assertThat(conversation.getValue()).matches("[A-Za-z0-9_-]{43}");
        // The agent gets the accounts and counts, not who the report belongs to.
        assertThat(sent.getValue().fullName()).isNull();
        assertThat(sent.getValue().ssnMasked()).isNull();
        assertThat(sent.getValue().reportId()).isNull();
        assertThat(sent.getValue().accounts()).isEqualTo(REPORT.accounts());
    }

    @Test
    void theChatNeedsALogin() throws Exception {
        mvc.perform(post(ChatController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ssn\":\"" + SSN + "\",\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        verify(advisor, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void anotherPersonsSsnIs403BeforeAnyReportIsRead() throws Exception {
        mvc.perform(chat("234-56-7890", "What is my score?")).andExpect(status().isForbidden());
        verify(reports, never()).findReport(any());
        verify(advisor, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void badInputIs400() throws Exception {
        mvc.perform(chat("not-an-ssn", "hi")).andExpect(status().isBadRequest());
        mvc.perform(chat(SSN, "")).andExpect(status().isBadRequest());
        mvc.perform(chat(SSN, "x".repeat(1001))).andExpect(status().isBadRequest());
    }

    @Test
    void noReportIs404AndAnOutageIs503() throws Exception {
        given(reports.findReport(Ssn.parse(SSN))).willReturn(Optional.empty());
        mvc.perform(chat(SSN, "What is my score?")).andExpect(status().isNotFound());

        given(reports.findReport(Ssn.parse(SSN))).willReturn(Optional.of(REPORT));
        given(advisor.chat(anyString(), anyString(), any())).willThrow(new ServiceUnavailableException("down", null));
        mvc.perform(chat(SSN, "What is my score?"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("assistant is unavailable")));
    }

    @Test
    void loggingOutForgetsTheConversationAndSurvivesTheAgentBeingDown() throws Exception {
        mvc.perform(chat(SSN, "What is my utilization?")).andExpect(status().isOk());
        ArgumentCaptor<String> conversation = ArgumentCaptor.forClass(String.class);
        verify(advisor).chat(conversation.capture(), anyString(), any());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer)).andExpect(status().isNoContent());
        verify(advisor).forget(conversation.getValue());

        signIn();
        willThrow(new ServiceUnavailableException("down", null)).given(advisor).forget(anyString());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer)).andExpect(status().isNoContent());
    }

    @Test
    void theForgetRunsOffTheLogoutThread() throws Exception {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ChatService background = new ChatService(auth, reports, advisor, queued::add);
        background.sessionEnded(new com.creditagent.web.auth.AuthenticatedUser("avery", "session", "h", "6789"));
        verify(advisor, never()).forget(anyString());   // nothing ran on the caller's thread
        willThrow(new ServiceUnavailableException("down", null)).given(advisor).forget(anyString());
        queued.getFirst().run();                         // and a failure there is swallowed
        verify(advisor).forget(ChatService.conversationId(
                new com.creditagent.web.auth.AuthenticatedUser("avery", "session", "h", "6789")));
    }

    @Test
    void eachLoginIsANewConversation() throws Exception {
        mvc.perform(chat(SSN, "first")).andExpect(status().isOk());
        String firstBearer = bearer;
        signIn();
        assertThat(bearer).isNotEqualTo(firstBearer);
        mvc.perform(chat(SSN, "second")).andExpect(status().isOk());
        ArgumentCaptor<String> conversations = ArgumentCaptor.forClass(String.class);
        verify(advisor, org.mockito.Mockito.times(2)).chat(conversations.capture(), anyString(), any());
        assertThat(conversations.getAllValues().get(0)).isNotEqualTo(conversations.getAllValues().get(1));
    }
}
