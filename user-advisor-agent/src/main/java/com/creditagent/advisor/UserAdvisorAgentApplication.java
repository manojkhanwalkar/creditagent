package com.creditagent.advisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UserAdvisorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAdvisorAgentApplication.class, args);
    }
}
