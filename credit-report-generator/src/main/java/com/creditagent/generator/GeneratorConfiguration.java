package com.creditagent.generator;

import com.creditagent.common.AuditLog;
import com.creditagent.web.support.RequestSizeLimitFilter;
import com.creditagent.web.support.ServiceTokenFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.creditagent.reports.CreditReportsConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties(GeneratorProperties.class)
@Import(CreditReportsConfiguration.class)
class GeneratorConfiguration {

    /** A request is an SSN and an optional name. */
    static final long MAX_REQUEST_BYTES = 16 * 1024;

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_REQUEST_BYTES, "/api/*", "/internal/*");
    }

    /**
     * Every API needs the service token: erasing a consumer's reports (the web server) and generating
     * them, which replaces whatever is stored for that SSN (S-14). Ordered before the inbound limits, so
     * callers without it cannot use up the shared bucket. Health stays open.
     */
    @Bean
    FilterRegistrationBean<ServiceTokenFilter> internalApiTokenFilter(GeneratorProperties properties) {
        return ServiceTokenFilter.registration(properties.serviceToken());
    }

    @Bean
    AuditLog auditLog() {
        return new AuditLog();
    }

    @Bean
    CreditReportFactory creditReportFactory() {
        return new CreditReportFactory(new SecureRandom(), Clock.systemUTC());
    }

    @Bean
    BureauReportFormatter bureauReportFormatter() {
        return new BureauReportFormatter(new SecureRandom());
    }
}
