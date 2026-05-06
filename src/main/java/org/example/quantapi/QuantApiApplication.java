package org.example.quantapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuantApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuantApiApplication.class, args);
    }
}