package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.MarketHistory;
import com.quantshine.capital.quantshine_capital.repository.MarketHistoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Piyasa verisi servisi.
 *
 * Veri kaynak önceliği:
 *   1) Yerel DB (MarketHistory) — günlük TradingView Scanner snapshot'ları burada birikir
 *   2) Yahoo Finance (yedek) — Yahoo 429/crumb ile kısıtlı; çalışırsa kullanılır
 *   3) TradingView Scanner anlık fiyatı → geçmişi olmayan sembol için düz (flat) seri
 *
 * Random-walk sahte veri üreten eski fallback KALDIRILDI (yanıltıcıydı).
 */
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final RestTemplate restTemplate;
    private final MarketHistoryRepository marketHistoryRepository;

    private static final String YAHOO_BASE    = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String TV_SCAN_BASE  = "https://scanner.tradingview.com/";

    /* ─── Sembol haritası (frontend → Yahoo / TradingView / region) ─ */
    private record SymbolDef(String yahoo, String tvTicker, String tvRegion) {}

    private static final Map<String, SymbolDef> SYMBOLS = Map.of(
        "USD",    new SymbolDef("USDTRY=X",  "FX_IDC:USDTRY", "forex"),
        "EUR",    new SymbolDef("EURTRY=X",  "FX_IDC:EURTRY", "forex"),
        "GOLD",   new SymbolDef("GC=F",      "TVC:GOLD",      "cfd"),
        "SILVER", new SymbolDef("SI=F",      "TVC:SILVER",    "cfd"),
        "BIST",   new SymbolDef("XU100.IS",  "BIST:XU100",    "turkey"),
        "THYAO",  new SymbolDef("THYAO.IS",  "BIST:THYAO",    "turkey")
    );

    /* ─── Ortak HTTP başlıkları ────────────────────────────────── */
    private HttpHeaders browserHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                          + "AppleWebKit/537.36 (KHTML, like Gecko) "
                          + "Chrome/122.0.0.0 Safari/537.36");
        h.set("Accept", "application/json");
        h.set("Accept-Language", "en-US,en;q=0.9");
        return h;
    }

    /* ─── PUBLIC API ───────────────────────────────────────────── */

    /**
     * Belirli bir sembolün son `days` gününe ait günlük kapanış serisi.
     * Dönüş: {yyyy-MM-dd → kapanış} (artık sıralı TreeMap).
     */
    public Map<String, Double> getMarketHistory(String symbol, int days) {
        String key = symbol == null ? "" : symbol.toUpperCase();
        SymbolDef def = SYMBOLS.get(key);
        if (def == null) {
            log.warn("Bilinmeyen sembol: {}", symbol);
            return Collections.emptyMap();
        }

        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days);

        // 1) DB'de biriken gerçek günlük snapshot'lar — en güvenilir kaynak
        Map<String, Double> fromDb = new TreeMap<>();
        marketHistoryRepository.findBySymbolAndDateGreaterThanEqualOrderByDateAsc(key, start)
                .forEach(h -> fromDb.put(h.getDate().toString(), h.getClose().doubleValue()));

        // Yeterli veri varsa dön
        if (fromDb.size() >= Math.min(days, 5)) {
            return fromDb;
        }

        // 2) Yahoo'yu dene (backend IP'sinden rate-limit'e takılmayabilir)
        Map<String, Double> fromYahoo = fetchYahooHistory(def.yahoo(), days);
        if (!fromYahoo.isEmpty()) {
            // DB'ye yaz → gelecekte kullanılsın
            persistHistory(key, fromYahoo);
            return fromYahoo;
        }

        // 3) Son çare: TV Scanner'dan anlık fiyatı çek; yalnızca BUGÜN kaydı olarak dön.
        //    Flat seri yanıltıcıydı — zamanla scheduler DB'ye gerçek değişimi biriktirecek.
        Double currentPrice = fetchTvScannerPrice(def.tvTicker(), def.tvRegion());
        if (currentPrice != null) {
            persistSingleSnapshot(key, today, currentPrice);
            // DB'de önceki günlere ait ne varsa birlikte dön
            Map<String, Double> combined = new TreeMap<>(fromDb);
            combined.put(today.toString(), currentPrice);
            return combined;
        }

        // Hiçbir kaynak çalışmadıysa, elimizde DB'de ne varsa onu dön
        log.warn("{} için canlı veri alınamadı — DB'deki {} kayıt döndürülüyor", symbol, fromDb.size());
        return fromDb;
    }

    /* ─── Scheduler: günlük snapshot kaydı ──────────────────────── */

    /**
     * Startup'ta: DB'de hiç kayıt yoksa Yahoo'dan son 90 günü çekip seed'ler.
     * Böylece ilk kurulumda grafik boş kalmaz.
     */
    @PostConstruct
    public void seedHistoryIfEmpty() {
        // Ayrı thread'de çalışsın ki app startup'ını bloke etmesin
        new Thread(() -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
            SYMBOLS.forEach((key, def) -> {
                try {
                    long existing = marketHistoryRepository
                            .findBySymbolAndDateGreaterThanEqualOrderByDateAsc(key, LocalDate.now().minusDays(90))
                            .size();
                    if (existing < 10) {
                        Map<String, Double> hist = fetchYahooHistory(def.yahoo(), 90);
                        if (!hist.isEmpty()) {
                            persistHistory(key, hist);
                            log.info("{} için {} gün seed kaydedildi", key, hist.size());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Seed atlanıyor [{}]: {}", key, e.getMessage());
                }
            });
        }, "market-history-seed").start();
    }

    /**
     * Her gün 18:30 (TR) — BIST kapanışından sonra — tüm sembollerin günlük
     * kapanış snapshot'ını DB'ye kaydeder.
     */
    @Scheduled(cron = "0 30 18 * * *", zone = "Europe/Istanbul")
    public void captureDailySnapshots() {
        log.info("Günlük piyasa snapshot kaydı başlıyor...");
        LocalDate today = LocalDate.now();
        SYMBOLS.forEach((key, def) -> {
            try {
                Double price = fetchTvScannerPrice(def.tvTicker(), def.tvRegion());
                if (price != null) {
                    persistSingleSnapshot(key, today, price);
                    log.info("Snapshot kaydedildi [{}]: {}", key, price);
                } else {
                    // TV başarısızsa Yahoo'dan son günü çek
                    Map<String, Double> hist = fetchYahooHistory(def.yahoo(), 5);
                    if (!hist.isEmpty()) {
                        persistHistory(key, hist);
                    }
                }
            } catch (Exception e) {
                log.warn("Günlük snapshot hatası [{}]: {}", key, e.getMessage());
            }
        });
    }

    /**
     * TradingView Scanner'dan anlık kapanış fiyatı çeker.
     * Ücretsiz endpoint — kimlik doğrulama yok.
     */
    public Double fetchTvScannerPrice(String tvTicker, String region) {
        try {
            String url = TV_SCAN_BASE + region + "/scan";
            HttpHeaders h = browserHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("Origin", "https://www.tradingview.com");
            h.set("Referer", "https://www.tradingview.com/");

            String body = """
                {"symbols":{"tickers":["%s"]},"columns":["close"]}
                """.formatted(tvTicker);

            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);

            if (response.getBody() == null) return null;
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> data = (List<Map<String,Object>>) response.getBody().get("data");
            if (data == null || data.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            List<Object> d = (List<Object>) data.get(0).get("d");
            if (d == null || d.isEmpty() || d.get(0) == null) return null;

            double price = Double.parseDouble(d.get(0).toString());
            return Math.round(price * 10000.0) / 10000.0;

        } catch (Exception e) {
            log.warn("TV Scanner fiyat çekme hatası [{}]: {}", tvTicker, e.getMessage());
            return null;
        }
    }

    /* ─── INTERNAL ───────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private Map<String, Double> fetchYahooHistory(String yahooSymbol, int days) {
        String range, interval;
        if (days <= 7)       { range = "5d";  interval = "1h"; }
        else if (days <= 30) { range = "1mo"; interval = "1d"; }
        else if (days <= 90) { range = "3mo"; interval = "1d"; }
        else                 { range = "1y";  interval = "1wk"; }

        try {
            String url = YAHOO_BASE + yahooSymbol + "?interval=" + interval + "&range=" + range;
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(browserHeaders()), Map.class);

            if (response.getBody() == null) return Collections.emptyMap();
            Map<String, Object> chart = (Map<String, Object>) response.getBody().get("chart");
            if (chart == null) return Collections.emptyMap();
            List<Map<String, Object>> rs = (List<Map<String, Object>>) chart.get("result");
            if (rs == null || rs.isEmpty()) return Collections.emptyMap();

            Map<String, Object> result        = rs.get(0);
            List<Long>          timestamps    = (List<Long>) result.get("timestamp");
            Map<String, Object> indicators    = (Map<String, Object>) result.get("indicators");
            if (timestamps == null || indicators == null) return Collections.emptyMap();
            List<Map<String, Object>> quotes  = (List<Map<String, Object>>) indicators.get("quote");
            if (quotes == null || quotes.isEmpty()) return Collections.emptyMap();
            List<?> closes = (List<?>) quotes.get(0).get("close");
            if (closes == null) return Collections.emptyMap();

            Map<String, Double> history = new TreeMap<>();
            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i) == null) continue;
                double price = toDouble(closes.get(i));
                LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                        .atZone(ZoneId.of("UTC"))
                        .toLocalDate();
                history.put(date.toString(), Math.round(price * 100.0) / 100.0);
            }
            return history;

        } catch (Exception e) {
            log.warn("Yahoo Finance başarısız [{}]: {}", yahooSymbol, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void persistHistory(String symbol, Map<String, Double> history) {
        history.forEach((dateStr, price) -> {
            try {
                LocalDate date = LocalDate.parse(dateStr);
                persistSingleSnapshot(symbol, date, price);
            } catch (Exception ignored) {}
        });
    }

    private void persistSingleSnapshot(String symbol, LocalDate date, double price) {
        try {
            MarketHistory existing = marketHistoryRepository
                    .findBySymbolAndDate(symbol, date).orElse(new MarketHistory());
            existing.setSymbol(symbol);
            existing.setDate(date);
            existing.setClose(new java.math.BigDecimal(price).setScale(4, java.math.RoundingMode.HALF_UP));
            marketHistoryRepository.save(existing);
        } catch (Exception e) {
            log.debug("Snapshot kayıt hatası [{}/{}]: {}", symbol, date, e.getMessage());
        }
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return 0.0; }
    }
}
