package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.MarketHistory;
import com.quantshine.capital.quantshine_capital.entity.Stock;
import com.quantshine.capital.quantshine_capital.repository.MarketHistoryRepository;
import com.quantshine.capital.quantshine_capital.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TradingViewStockUpdateService {

    private static final Logger log = LoggerFactory.getLogger(TradingViewStockUpdateService.class);

    private final StockRepository stockRepository;
    private final MarketHistoryRepository marketHistoryRepository;
    private final RestTemplate restTemplate;
    private final FundService fundService;
    private final MarketDataService marketDataService;

    private static final String TV_URL =
            "https://scanner.tradingview.com/turkey/scan";

    /**
     * Uygulama açılışında, DB'de hiç hisse yoksa otomatik doldur.
     * Böylece yeni kurulumlarda/temiz DB'lerde manuel tetikleme gerekmez.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        long count = stockRepository.count();
        if (count == 0) {
            log.info("Başlangıçta stok tablosu boş — TradingView'dan ilk yükleme başlatılıyor...");
            new Thread(() -> {
                try {
                    updateStocksAndFunds();
                } catch (Exception e) {
                    log.error("Başlangıç yüklemesi hatası: {}", e.getMessage(), e);
                }
            }, "tv-startup-fetch").start();
        } else {
            log.info("Stok tablosunda {} kayıt mevcut; başlangıç güncellemesi atlandı.", count);
        }
    }

    /**
     * BIST işlem saatlerinde her 15 dakikada bir fiyat güncelle.
     * Pazartesi-Cuma 10:00-18:00, Türkiye saati.
     * TradingView Scanner free tier ~15dk gecikmeli veri sunar; daha sık istek gereksiz.
     */
    @Scheduled(cron = "0 */15 10-17 * * MON-FRI", zone = "Europe/Istanbul")
    public void updateDuringMarketHours() {
        log.info("Piyasa saati güncellemesi başlatılıyor... {}", LocalDateTime.now());
        updateStocksAndFunds();
    }

    /** Hem hisse fiyatlarını hem de tüm fon birim fiyatlarını günceller. */
    public void updateAllBistStocks() {
        updateStocksAndFunds();
    }

    private void updateStocksAndFunds() {
        try {
            int updated = fetchAndSaveAll();
            log.info("{} hisse güncellendi. Fon fiyatları hesaplanıyor...", updated);
            fundService.updateAllFundPrices();
            captureMarketSnapshots();
        } catch (Exception e) {
            log.error("Güncelleme hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Piyasa referans sembolleri (BIST100, USDTRY, EURTRY, GOLD, SILVER, THYAO)
     * için anlık fiyatı TradingView Scanner'dan alıp günlük snapshot olarak
     * market_history tablosuna yazar. Aynı gün için varsa günceller.
     * Böylece günler geçtikçe gerçek benchmark tarihsel serisi birikir.
     */
    @Transactional
    public void captureMarketSnapshots() {
        LocalDate today = LocalDate.now();
        Map<String, String[]> targets = Map.of(
            "USD",    new String[]{"FX_IDC:USDTRY", "forex"},
            "EUR",    new String[]{"FX_IDC:EURTRY", "forex"},
            "BIST",   new String[]{"BIST:XU100",    "turkey"},
            "GOLD",   new String[]{"TVC:GOLD",      "cfd"},
            "SILVER", new String[]{"TVC:SILVER",    "cfd"},
            "THYAO",  new String[]{"BIST:THYAO",    "turkey"}
        );

        int saved = 0;
        for (Map.Entry<String, String[]> entry : targets.entrySet()) {
            try {
                Double price = marketDataService.fetchTvScannerPrice(
                        entry.getValue()[0], entry.getValue()[1]);
                if (price == null) continue;

                MarketHistory row = marketHistoryRepository
                        .findBySymbolAndDate(entry.getKey(), today)
                        .orElse(new MarketHistory());
                row.setSymbol(entry.getKey());
                row.setDate(today);
                row.setClose(BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP));
                marketHistoryRepository.save(row);
                saved++;
            } catch (Exception e) {
                log.warn("Piyasa snapshot hatası [{}]: {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("{} piyasa sembolü için günlük snapshot kaydedildi", saved);
    }

    @Transactional
    public int fetchAndSaveAll() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/122.0.0.0 Safari/537.36");
            headers.set("Origin", "https://www.tradingview.com");
            headers.set("Referer", "https://www.tradingview.com/");

            String requestBody = """
                {
                  "filter": [],
                  "options": {"lang": "tr"},
                  "symbols": {
                    "tickers": [],
                    "query": {"types": ["stock"]}
                  },
                  "columns": [
                    "name",
                    "description",
                    "close",
                    "change",
                    "change_abs",
                    "volume",
                    "market_cap_basic",
                    "sector"
                  ],
                  "sort": {
                    "sortBy": "market_cap_basic",
                    "sortOrder": "desc"
                  },
                  "range": [0, 600]
                }
                """;

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            log.debug("TradingView Scanner'a istek gönderiliyor...");

            ResponseEntity<Map> response = restTemplate.exchange(
                    TV_URL, HttpMethod.POST, entity, Map.class
            );

            if (response.getBody() == null) {
                log.warn("TradingView'dan boş yanıt alındı");
                return 0;
            }

            Map<String, Object> body = response.getBody();
            log.debug("TradingView toplam hisse: {}", body.get("totalCount"));

            List<Map<String, Object>> dataList =
                    (List<Map<String, Object>>) body.get("data");

            if (dataList == null || dataList.isEmpty()) {
                log.warn("TradingView data listesi boş");
                return 0;
            }

            log.info("{} hisse verisi alındı, DB'ye yazılıyor...", dataList.size());

            int count = 0;
            int skipCount = 0;

            for (Map<String, Object> item : dataList) {
                try {
                    String symbol = (String) item.get("s"); // BIST:THYAO
                    List<Object> values = (List<Object>) item.get("d");

                    if (symbol == null || values == null) {
                        skipCount++;
                        continue;
                    }

                    // BIST:THYAO → THYAO
                    String stockCode = symbol.contains(":")
                            ? symbol.split(":")[1].toUpperCase()
                            : symbol.toUpperCase();

                    // Hisse adı
                    String stockName = values.get(1) != null
                            ? values.get(1).toString()
                            : stockCode;

                    // Kapanış fiyatı
                    Object closeObj = values.get(2);
                    if (closeObj == null) {
                        skipCount++;
                        continue;
                    }

                    BigDecimal currentPrice = new BigDecimal(closeObj.toString())
                            .setScale(2, RoundingMode.HALF_UP);

                    if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        skipCount++;
                        continue;
                    }

                    // Değişim %
                    BigDecimal changePct = values.get(3) != null
                            ? new BigDecimal(values.get(3).toString())
                            .setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    // Değişim TL
                    BigDecimal changeAbs = values.get(4) != null
                            ? new BigDecimal(values.get(4).toString())
                            .setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    // Önceki kapanış
                    BigDecimal previousClose = currentPrice.subtract(changeAbs);

                    // DB'ye kaydet
                    saveStock(stockCode, stockName, currentPrice,
                            previousClose, changeAbs, changePct);

                    count++;

                    // Her 50 hissede bir log
                    if (count % 50 == 0) {
                        log.debug("{} hisse kaydedildi...", count);
                    }

                } catch (Exception e) {
                    skipCount++;
                    log.warn("Parse hatası: {}", e.getMessage());
                }
            }

            log.info("Sonuç → Kaydedilen: {}, Atlanan: {}", count, skipCount);
            return count;

        } catch (Exception e) {
            log.error("fetchAndSaveAll hatası: {}", e.getMessage(), e);
            throw new RuntimeException("TradingView güncelleme başarısız", e);
        }
    }

    @Transactional
    public void saveStock(String stockCode, String stockName,
                          BigDecimal currentPrice, BigDecimal previousClose,
                          BigDecimal change, BigDecimal changePct) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElse(new Stock());

        stock.setStockCode(stockCode);
        stock.setStockName(stockName);
        stock.setCurrentPrice(currentPrice);
        stock.setPreviousClose(previousClose);
        stock.setChange(change);
        stock.setChangePercent(changePct + "%");
        stock.setLastUpdate(LocalDateTime.now());

        stockRepository.save(stock);
    }

    @Transactional
    public void updateSpecificStocks(List<String> stockCodes) {
        fetchAndSaveAll(); // Tümünü çek, hepsi DB'ye yazılır
    }

    public int getTotalStockCount() {
        // DB'deki toplam hisse sayısını döndür
        return (int) stockRepository.count();
    }
}