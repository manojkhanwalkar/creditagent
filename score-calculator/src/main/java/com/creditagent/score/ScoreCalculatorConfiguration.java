package com.creditagent.score;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ScoreCalculatorConfiguration {

    @Bean
    ScoreCalculator scoreCalculator() {
        return new FactorBasedScoreCalculator();
    }
}
