package com.creditagent.generator;

import com.creditagent.common.CreditFileNames;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeneratorProperties.class)
class GeneratorConfiguration {

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
