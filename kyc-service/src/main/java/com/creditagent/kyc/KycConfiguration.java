package com.creditagent.kyc;

import com.creditagent.web.support.RequestSizeLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KycProperties.class)
class KycConfiguration {

    /** Multipart framing adds a little to the image; anything far beyond it is refused before parsing. */
    static final long MULTIPART_OVERHEAD_BYTES = 64 * 1024;

    @Bean
    SsnDocumentVerifier ssnDocumentVerifier(KycProperties properties) {
        return new SsnDocumentVerifier(properties);
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(KycProperties properties) {
        return RequestSizeLimitFilter.registration(properties.maxImageBytes() + MULTIPART_OVERHEAD_BYTES, "/api/*");
    }
}
