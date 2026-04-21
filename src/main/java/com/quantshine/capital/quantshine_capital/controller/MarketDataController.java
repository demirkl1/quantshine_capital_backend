package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.service.MarketDataService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "http://localhost:3000")
public class MarketDataController {

    private final MarketDataService marketDataService;

    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /**
     * Fon vs benchmark karşılaştırma grafiği için günlük geçmiş.
     * AdminAnasayfa'da fon getirisini BIST/USD ile kıyaslamak için kullanılır.
     */
    @GetMapping("/history/{symbol}")
    public Map<String, Double> getMarketHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "30") int days) {
        return marketDataService.getMarketHistory(symbol, days);
    }
}
