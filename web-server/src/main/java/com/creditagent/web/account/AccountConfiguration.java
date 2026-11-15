package com.creditagent.web.account;

import com.creditagent.clients.CreditReportSource;
import com.creditagent.clients.HttpClients;
import com.creditagent.clients.RetrySettings;
import com.creditagent.common.AuditLog;
import com.creditagent.web.alerts.AlertsService;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.SessionEndListener;
import com.creditagent.web.auth.SessionRepository;
import com.creditagent.web.auth.UserRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountProperties.class)
class AccountConfiguration {

    /** The erase is idempotent, so transient failures are retried with backoff and jitter. */
    @Bean
    ReportEraseClient reportEraseClient(RestClient.Builder builder, AccountProperties properties, RetrySettings retry,
            @Value("${credit.internal.token:}") String serviceToken) {
        return new ReportEraseClient(HttpClients.restClient(builder, properties.reportsUrl(), properties.timeout(), retry),
                serviceToken);
    }

    @Bean
    AccountService accountService(AuthService auth, UserRepository users, SessionRepository sessions, AlertsService alerts,
            ReportEraseClient reports, CreditReportSource reportSource, ObjectProvider<SessionEndListener> listeners,
            AuditLog audit) {
        return new AccountService(auth, users, sessions, alerts, reports, reportSource, listeners.orderedStream().toList(),
                audit, Clock.systemUTC());
    }

    @Bean
    AccountRetentionJob accountRetentionJob(UserRepository users, AccountService accounts, AccountProperties properties) {
        return new AccountRetentionJob(users, accounts, properties, Clock.systemUTC());
    }
}
