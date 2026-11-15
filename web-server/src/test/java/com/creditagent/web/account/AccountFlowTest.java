package com.creditagent.web.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.AuditLog;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.web.WebServerProperties;
import com.creditagent.web.alerts.AlertsService;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.FakeIdentityVerifier;
import com.creditagent.web.auth.InMemoryRepositories;
import com.creditagent.web.auth.JwtService;
import com.creditagent.web.auth.SsnHasher;
import com.creditagent.web.auth.TestSecrets;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Export and deletion through the real token filter and login endpoint. */
@WebMvcTest(controllers = AccountController.class)
@Import({AccountFlowTest.Wiring.class, com.creditagent.web.auth.AuthControllerConfig.class})
@TestPropertySource(properties = {
    "credit.web.mcp-url=http://mcp.invalid/mcp",
    "credit.web.score-calculator-url=http://score.invalid",
    "credit.web.timeout=1s",
    "credit.web.rate-limit-per-minute=1000"
})
class AccountFlowTest {

    static final String PASSWORD = "correct horse battery staple";

    @TestConfiguration
    @EnableConfigurationProperties(WebServerProperties.class)
    static class Wiring {
        static final InMemoryRepositories.MutableClock CLOCK =
                new InMemoryRepositories.MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
        static final InMemoryRepositories.Users USERS = new InMemoryRepositories.Users();
        static final InMemoryRepositories.Sessions SESSIONS = new InMemoryRepositories.Sessions();

        @Bean
        AuthService authService() {
            return new AuthService(USERS, SESSIONS, new BCryptPasswordEncoder(4),
                    new SsnHasher(TestSecrets.of("ssn")), new FakeIdentityVerifier(),
                    new JwtService(TestSecrets.of("jwt"), "credit-agent", Duration.ofHours(1), CLOCK),
                    Duration.ofMinutes(5), CLOCK);
        }

        @Bean
        AccountService accountService(AuthService auth, AlertsService alerts, ReportEraseClient reports, CreditReportSource source) {
            return new AccountService(auth, USERS, SESSIONS, alerts, reports, source, List.of(), new AuditLog(), Clock.systemUTC());
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthService auth;

    @MockitoBean
    AlertsService alerts;

    @MockitoBean
    ReportEraseClient reports;

    @MockitoBean
    CreditReportSource source;

    @MockitoBean
    com.creditagent.web.CreditScoreService scores;

    @BeforeEach
    void registerUsers() {
        if (Wiring.USERS.find("ada").isEmpty()) {
            auth.register("ada", PASSWORD, "ada@example.com", "98101", FakeIdentityVerifier.card("123-45-6789"));
        }
    }

    String bearer(String user) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    @Test
    void exportIsADownloadOfTheUsersOwnData() throws Exception {
        given(source.findReport(Ssn.parse("123-45-6789"))).willReturn(Optional.of(
                new CreditReport("r-1", "***-**-6789", "Ada", Instant.EPOCH, List.of(), 0, 0, 0)));
        given(alerts.findingsFor("ada@example.com")).willReturn(List.of());
        mvc.perform(post("/api/v1/account/export").header("Authorization", bearer("ada"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.account.userId").value("ada"))
                .andExpect(jsonPath("$.account.ssnMasked").value("***-**-6789"))
                .andExpect(jsonPath("$.account.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.creditReport.reportId").value("r-1"));
    }

    @Test
    void exportRefusesSomeoneElsesSsnBadInputAndMissingTokens() throws Exception {
        String bearer = bearer("ada");
        mvc.perform(post("/api/v1/account/export").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"234-56-7890\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/account/export").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"666-12-3456\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/account/export").contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isUnauthorized());
        given(source.findReport(any())).willReturn(Optional.empty());
        given(alerts.findingsFor(anyString())).willThrow(new ServiceUnavailableException("down", null));
        mvc.perform(post("/api/v1/account/export").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"123-45-6789\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void deletionNeedsThePasswordAndSsnAndEndsTheSession() throws Exception {
        auth.register("bea", PASSWORD, "bea@example.com", "98101", FakeIdentityVerifier.card("234-56-7890"));
        String bearer = bearer("bea");
        mvc.perform(post("/api/v1/account/delete").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"not the password at all\",\"ssn\":\"234-56-7890\"}"))
                .andExpect(status().isForbidden());
        verify(reports, never()).erase(any());

        willThrow(new ServiceUnavailableException("down", null)).given(reports).erase(Ssn.parse("234-56-7890"));
        mvc.perform(post("/api/v1/account/delete").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"ssn\":\"234-56-7890\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(Wiring.USERS.find("bea")).isPresent();

        org.mockito.Mockito.reset(reports);
        mvc.perform(post("/api/v1/account/delete").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"ssn\":\"234-56-7890\"}"))
                .andExpect(status().isNoContent());
        assertThat(Wiring.USERS.find("bea")).isEmpty();
        mvc.perform(get("/api/v1/auth/session").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"bea\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExportForADeletedAccountWithALiveTokenIs401() throws Exception {
        auth.register("cal", PASSWORD, "cal@example.com", "98101", FakeIdentityVerifier.card("345-67-8901"));
        String bearer = bearer("cal");
        Wiring.USERS.byId.remove("cal"); // removed underneath an open session
        mvc.perform(post("/api/v1/account/export").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"345-67-8901\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUsersTableFailureIs503WithTheTryAgainMessage() throws Exception {
        auth.register("dee", PASSWORD, "dee@example.com", "98101", FakeIdentityVerifier.card("456-78-9013"));
        String bearer = bearer("dee");
        given(source.findReport(any())).willThrow(software.amazon.awssdk.core.exception.SdkClientException.create("dynamo down"));
        mvc.perform(post("/api/v1/account/export").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ssn\":\"456-78-9013\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(AccountController.TRY_AGAIN));
    }
}
