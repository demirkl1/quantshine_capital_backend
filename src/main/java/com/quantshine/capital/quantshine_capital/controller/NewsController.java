package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QuantShine Capital Haber Yönetim Kontrolcüsü
 * Finansal analizler ve güncel piyasa haberlerini servis eder.
 */
@RestController
@RequestMapping("/api/news")
// React (localhost:3000) erişimine izin veriyoruz
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;

    /**
     * En güncel finans ve borsa haberlerini getirir.
     * Bu veri ana sayfadaki blog/makale kısmını beslemek için kullanılır.
     */
    @GetMapping("/latest")
    public Object getLatestNews() {
        return newsService.getLatestFinanceNews();
    }
}