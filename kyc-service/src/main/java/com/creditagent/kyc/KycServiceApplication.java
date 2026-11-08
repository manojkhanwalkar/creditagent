package com.creditagent.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KycServiceApplication {

    public static void main(String[] args) {
        // No display in a container; image decoding and the sample cards need none.
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(KycServiceApplication.class, args);
    }
}
