package com.quantshine.capital.quantshine_capital.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.List;

@Service
public class NewsService {
    private final RestTemplate restTemplate;
    // NewsAPI'den alacağın ücretsiz anahtar
    private final String API_KEY = "a0f3363405cf48d19e485ec786581640";

    public NewsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Object getLatestFinanceNews() {
        // Finans ve Borsa odaklı haberleri Türkçe/İngilizce çeken URL
        // NewsService.java içinde
        String url = "https://newsapi.org/v2/everything?q=bitcoin+OR+stocks+OR+finance&language=en&pageSize=6&apiKey=" + API_KEY;
        return restTemplate.getForObject(url, Object.class);
    }
}