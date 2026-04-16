package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.Stock;
import com.quantshine.capital.quantshine_capital.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TradingViewStockUpdateService {

    private static final Logger log = LoggerFactory.getLogger(TradingViewStockUpdateService.class);

    private final StockRepository stockRepository;
    private final RestTemplate restTemplate;
    private final FundService fundService;

    private static final String TV_URL =
            "https://scanner.tradingview.com/turkey/scan";

    /**
     * Sabah 10:00 — BIST açılışında hisse fiyatlarını ve fon değerlerini güncelle.
     * Pazartesi-Cuma, Türkiye saati (Europe/Istanbul).
     */
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Europe/Istanbul")
    public void updateAtBistOpen() {
        log.info("BIST AÇILIŞ güncellemesi başlatılıyor... {}", LocalDateTime.now());
        updateStocksAndFunds();
    }

    /**
     * Öğleden sonra 16:00 — BIST kapanışına 2 saat kala hisse fiyatlarını ve fon değerlerini güncelle.
     * Pazartesi-Cuma, Türkiye saati (Europe/Istanbul).
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Europe/Istanbul")
    public void updateBeforeBistClose() {
        log.info("BIST KAPANIŞ ÖNCESİ güncellemesi başlatılıyor... {}", LocalDateTime.now());
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
        } catch (Exception e) {
            log.error("Güncelleme hatası: {}", e.getMessage(), e);
        }
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