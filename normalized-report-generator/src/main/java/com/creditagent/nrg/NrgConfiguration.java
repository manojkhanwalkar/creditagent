package com.creditagent.nrg;

import com.creditagent.web.support.RequestSizeLimitFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class NrgConfiguration {

    @Bean
    ReportNormalizer reportNormalizer() {
        return new ReportNormalizer(new BureauReportParser(), Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            @Value("${nrg.max-request-bytes:3200000}") long maxBytes) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(maxBytes));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
