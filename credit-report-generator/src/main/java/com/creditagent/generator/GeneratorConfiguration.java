package com.creditagent.generator;

import com.creditagent.common.CreditFileNames;
import com.creditagent.web.support.RequestSizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeneratorProperties.class)
class GeneratorConfiguration {

    /** A request is an SSN and an optional name. */
    static final long MAX_REQUEST_BYTES = 16 * 1024;

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_REQUEST_BYTES, "/api/*");
    }

    @Bean
    CreditFileNames creditFileNames(GeneratorProperties properties) {
        return new CreditFileNames(properties.hmacKey());
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
