package com.quantshine.capital.quantshine_capital.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final RestTemplate restTemplate;
    private final String apiKey;

    public NewsService(RestTemplate restTemplate,
                       @Value("${newsapi.key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
    }

    /**
     * En güncel finans/borsa haberlerini çeker. 5 dakika cache'lenir —
     * NewsAPI free tier günde 100 istekle sınırlı, ana sayfa polling'ini tolere eder.
     * Hata durumunda boş sonuç döner (500 fırlatmaz).
     */
    @Cacheable("news")
    public Object getLatestFinanceNews() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("NewsAPI anahtarı tanımlı değil (newsapi.key) — boş liste döndü");
            return Collections.singletonMap("articles", Collections.emptyList());
        }
        try {
            String url = "https://newsapi.org/v2/everything"
                    + "?q=bitcoin+OR+stocks+OR+finance+OR+borsa+OR+hisse"
                    + "&language=tr&pageSize=6&sortBy=publishedAt&apiKey=" + apiKey;
            Object tr = restTemplate.getForObject(url, Object.class);
            if (tr != null) return tr;
        } catch (Exception e) {
            log.warn("NewsAPI TR isteği başarısız: {}", e.getMessage());
        }
        try {
            String url = "https://newsapi.org/v2/everything"
                    + "?q=bitcoin+OR+stocks+OR+finance"
                    + "&language=en&pageSize=6&sortBy=publishedAt&apiKey=" + apiKey;
            return restTemplate.getForObject(url, Object.class);
        } catch (Exception e) {
            log.warn("NewsAPI EN isteği başarısız: {}", e.getMessage());
            return Collections.singletonMap("articles", Collections.emptyList());
        }
    }
}
