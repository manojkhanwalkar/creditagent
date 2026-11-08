package com.creditagent.score;

import com.creditagent.web.support.RequestSizeLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ScoreCalculatorConfiguration {

    @Bean
    ScoreCalculator scoreCalculator() {
        return new FactorBasedScoreCalculator();
    }

    /** 256 KiB holds a report with several hundred accounts, far more than a real file. */
    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            @Value("${score.max-request-bytes:262144}") long maxBytes) {
        return RequestSizeLimitFilter.registration(maxBytes, "/api/*");
    }
}
