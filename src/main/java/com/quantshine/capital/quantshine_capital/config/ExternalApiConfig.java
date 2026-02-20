package com.quantshine.capital.quantshine_capital.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ExternalApiConfig {

    @Bean
    public RestTemplate restTemplate() {
        // Builder kullanmadan doğrudan oluşturuyoruz.
        // Bu seni "cannot find symbol RestTemplateBuilder" hatasından kurtarır.
        return new RestTemplate();
    }
}