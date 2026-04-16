package com.quantshine.capital.quantshine_capital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuantshineCapitalApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuantshineCapitalApplication.class, args);
    }
}