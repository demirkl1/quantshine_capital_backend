package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.dto.MarketSummaryDTO;
import com.quantshine.capital.quantshine_capital.service.MarketDataService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.Map;
import java.util.TreeMap;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "http://localhost:3000")
public class MarketDataController {

    private final MarketDataService marketDataService;

    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/summary")
    public List<MarketSummaryDTO> getMarketSummary() {
        return marketDataService.getMarketSummary();
    }

    @GetMapping("/history/{symbol}")
    public Map<String, Double> getMarketHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "30") int days) {
        return marketDataService.getMarketHistory(symbol, days);
    }

    /**
     * Bireysel BIST hissesinin fiyat geçmişi.
     * Yahoo Finance'da BIST hisseleri STOCKCODE.IS formatındadır.
     * GET /api/market/stock-history/THYAO?days=30
     */
    @GetMapping("/stock-history/{stockCode}")
    public Map<String, Double> getStockHistory(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "30") int days) {
        return marketDataService.getStockHistory(stockCode, days);
    }
}
