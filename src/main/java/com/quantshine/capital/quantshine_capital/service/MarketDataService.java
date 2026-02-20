package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.dto.MarketSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final RestTemplate restTemplate;

    private static final String YAHOO_BASE = "https://query1.finance.yahoo.com/v8/finance/chart/";

    /* ─── Özet şeridi için semboller (sıralı) ─────────────────── */
    private static final LinkedHashMap<String, String> SUMMARY_SYMBOLS = new LinkedHashMap<>();
    static {
        SUMMARY_SYMBOLS.put("USD/TRY",   "USDTRY=X");
        SUMMARY_SYMBOLS.put("EUR/TRY",   "EURTRY=X");
        SUMMARY_SYMBOLS.put("ONS/ALTIN", "GC=F");
        SUMMARY_SYMBOLS.put("BIST 100",  "XU100.IS");
        SUMMARY_SYMBOLS.put("THY",       "THYAO.IS");
        SUMMARY_SYMBOLS.put("GÜMÜş",     "SI=F");
    }

    /* ─── Grafik geçmişi için semboller ───────────────────────── */
    private static final Map<String, String> HISTORY_SYMBOLS = Map.of(
        "USD",    "USDTRY=X",
        "EUR",    "EURTRY=X",
        "GOLD",   "GC=F",
        "BIST",   "XU100.IS",
        "THYAO",  "THYAO.IS",
        "SILVER", "SI=F"
    );

    /* ─── Ortak HTTP başlıkları ────────────────────────────────── */
    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                 + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                 + "Chrome/122.0.0.0 Safari/537.36");
        h.set("Accept",          "application/json");
        h.set("Accept-Language", "en-US,en;q=0.9");
        return h;
    }

    /* ─── Yahoo Finance'dan ham JSON çek ──────────────────────── */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchYahoo(String yahooSymbol, String range, String interval) {
        try {
            String url = YAHOO_BASE + yahooSymbol
                       + "?interval=" + interval
                       + "&range="    + range;
            HttpEntity<String> entity   = new HttpEntity<>(buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);
            if (response.getBody() != null) return response.getBody();
        } catch (Exception e) {
            log.warn("Yahoo Finance isteği başarısız [{}]: {}", yahooSymbol, e.getMessage());
        }
        return Collections.emptyMap();
    }

    /* ─── Ticker şeridi özeti ──────────────────────────────────── */
    @SuppressWarnings("unchecked")
    public List<MarketSummaryDTO> getMarketSummary() {
        List<MarketSummaryDTO> result = new ArrayList<>();

        for (Map.Entry<String, String> entry : SUMMARY_SYMBOLS.entrySet()) {
            String displayName  = entry.getKey();
            String yahooSymbol  = entry.getValue();
            try {
                Map<String, Object> data    = fetchYahoo(yahooSymbol, "5d", "1d");
                Map<String, Object> chart   = (Map<String, Object>) data.get("chart");
                List<Map<String, Object>> rs = (List<Map<String, Object>>) chart.get("result");
                if (rs == null || rs.isEmpty()) continue;

                Map<String, Object> meta    = (Map<String, Object>) rs.get(0).get("meta");
                double current  = toDouble(meta.get("regularMarketPrice"));
                double prevClose = toDouble(meta.get("previousClose"));
                double changeRate = prevClose > 0
                        ? Math.round((current - prevClose) / prevClose * 10_000.0) / 100.0
                        : 0.0;
                boolean rising  = current >= prevClose;

                result.add(new MarketSummaryDTO(displayName, current, changeRate, rising));
            } catch (Exception e) {
                log.warn("Özet hatası [{}]: {}", displayName, e.getMessage());
            }
        }
        return result;
    }

    /* ─── Grafik geçmişi ───────────────────────────────────────── */
    @SuppressWarnings("unchecked")
    public Map<String, Double> getMarketHistory(String symbol, int days) {
        String yahooSymbol = HISTORY_SYMBOLS.getOrDefault(symbol.toUpperCase(), "USDTRY=X");

        String range, interval;
        if (days <= 7) {
            range = "5d";  interval = "1h";
        } else if (days <= 30) {
            range = "1mo"; interval = "1d";
        } else if (days <= 90) {
            range = "3mo"; interval = "1d";
        } else {
            range = "1y";  interval = "1wk";
        }

        try {
            Map<String, Object> data    = fetchYahoo(yahooSymbol, range, interval);
            Map<String, Object> chart   = (Map<String, Object>) data.get("chart");
            List<Map<String, Object>> rs = (List<Map<String, Object>>) chart.get("result");
            if (rs == null || rs.isEmpty()) return fallback(symbol, days);

            Map<String, Object>        result     = rs.get(0);
            List<Long>                 timestamps = (List<Long>) result.get("timestamp");
            Map<String, Object>        indicators = (Map<String, Object>) result.get("indicators");
            List<Map<String, Object>>  quotes     = (List<Map<String, Object>>) indicators.get("quote");
            List<?>                    closes     = (List<?>) quotes.get(0).get("close");

            Map<String, Double> history = new TreeMap<>();
            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i) == null) continue;
                double price = toDouble(closes.get(i));
                LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                        .atZone(ZoneId.of("UTC"))
                        .toLocalDate();
                history.put(date.toString(), Math.round(price * 100.0) / 100.0);
            }
            return history.isEmpty() ? fallback(symbol, days) : history;

        } catch (Exception e) {
            log.error("Grafik geçmişi hatası [{}]: {}", symbol, e.getMessage());
            return fallback(symbol, days);
        }
    }

    /* ─── Yahoo erişilemezse son bilinen yaklaşık fiyat ────────── */
    private Map<String, Double> fallback(String symbol, int days) {
        Map<String, Double> data = new TreeMap<>();
        LocalDate today = LocalDate.now();
        double base = switch (symbol.toUpperCase()) {
            case "USD"    -> 38.0;
            case "EUR"    -> 41.5;
            case "GOLD"   -> 2650.0;
            case "BIST"   -> 9500.0;
            case "THYAO"  -> 320.0;
            case "SILVER" -> 30.5;
            default       -> 100.0;
        };
        for (int i = days; i >= 0; i--) {
            base += (Math.random() * 2 - 1) * (base * 0.008);
            data.put(today.minusDays(i).toString(), Math.round(base * 100.0) / 100.0);
        }
        return data;
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return 0.0; }
    }
}
