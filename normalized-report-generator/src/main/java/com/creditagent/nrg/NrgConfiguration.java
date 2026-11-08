package com.creditagent.nrg;

import com.creditagent.web.support.RequestSizeLimitFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NrgConfiguration {

    @Bean
    ReportNormalizer reportNormalizer() {
        return new ReportNormalizer(new BureauReportParser(), Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            @Value("${nrg.max-request-bytes:3200000}") long maxBytes) {
        return RequestSizeLimitFilter.registration(maxBytes, "/api/*");
    }
}
