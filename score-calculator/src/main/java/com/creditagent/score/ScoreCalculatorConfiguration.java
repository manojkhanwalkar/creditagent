package com.creditagent.score;

import java.security.SecureRandom;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
class ScoreCalculatorConfiguration {

    @Bean
    ScoreCalculator scoreCalculator() {
        return new RandomScoreCalculator(new SecureRandom());
    }
}
