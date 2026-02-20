package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.entity.Stock;
import com.quantshine.capital.quantshine_capital.service.StockService;
import com.quantshine.capital.quantshine_capital.service.TradingViewStockUpdateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final StockService stockService;
    private final TradingViewStockUpdateService tradingViewStockUpdateService;

    // Tüm hisseleri getir
    @GetMapping
    public ResponseEntity<List<Stock>> getAllStocks() {
        List<Stock> stocks = stockService.getAllStocks();
        log.debug("getAllStocks: {} hisse döndü", stocks.size());
        return ResponseEntity.ok(stocks);
    }

    // TradingView ile tüm BIST güncelle
    @PostMapping("/update-all")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<String> updateAllStocks() {
        new Thread(() -> {
            try {
                tradingViewStockUpdateService.updateAllBistStocks();
            } catch (Exception e) {
                log.error("BIST güncelleme hatası: {}", e.getMessage());
            }
        }).start();

        return ResponseEntity.ok("✅ TradingView'dan " +
                tradingViewStockUpdateService.getTotalStockCount() +
                " BIST hissesi arka planda güncelleniyor...");
    }

    // Manuel tek hisse fiyat güncelle
    @PostMapping("/manual-price")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> manualPriceUpdate(
            @RequestBody Map<String, Object> payload) {
        try {
            String stockCode = ((String) payload.get("stockCode")).toUpperCase();
            BigDecimal price = new BigDecimal(payload.get("price").toString());
            String stockName = payload.get("stockName") != null
                    ? payload.get("stockName").toString()
                    : stockCode;

            Stock updated = stockService.updateStockPrice(
                    stockCode, stockName, price, BigDecimal.ZERO, "0%");

            log.info("Manuel güncelleme: {} = ₺{}", stockCode, price);
            return ResponseEntity.ok(updated);

        } catch (Exception e) {
            log.error("Manuel güncelleme hatası: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Hata: " + e.getMessage());
        }
    }

    // Eski update-price endpoint - geriye dönük uyumluluk
    @PostMapping("/update-price")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> updateStockPrice(
            @RequestBody Map<String, Object> payload) {
        try {
            String stockCode = (String) payload.get("stockCode");

            if (payload.get("currentPrice") == null) {
                return ResponseEntity.badRequest()
                        .body("currentPrice boş olamaz");
            }

            BigDecimal currentPrice = new BigDecimal(
                    payload.get("currentPrice").toString());
            BigDecimal change = payload.get("change") != null
                    ? new BigDecimal(payload.get("change").toString())
                    : BigDecimal.ZERO;
            String changePercent = payload.get("changePercent") != null
                    ? payload.get("changePercent").toString()
                    : "0%";
            String stockName = payload.get("stockName") != null
                    ? payload.get("stockName").toString()
                    : stockCode;

            Stock updated = stockService.updateStockPrice(
                    stockCode, stockName, currentPrice, change, changePercent);

            log.info("{} güncellendi: ₺{}", stockCode, currentPrice);
            return ResponseEntity.ok(updated);

        } catch (Exception e) {
            log.error("Güncelleme hatası: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Hata: " + e.getMessage());
        }
    }
}