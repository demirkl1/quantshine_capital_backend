package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.Stock;
import com.quantshine.capital.quantshine_capital.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Investing.com chart widget (sslcharts.investing.com) pair_ID zorunlu.
 * Bu servis BIST hisse kodu → pair_ID dönüşümünü Investing.com arama
 * API'leri üzerinden yapar ve sonucu stocks.investing_pair_id kolonunda
 * cache'ler.
 *
 * İki arama endpoint'i sırayla denenir:
 *  1) /search/service/searchTopBar (POST form)
 *  2) /search/service/search       (GET)
 * Biri 403 ya da boş dönerse diğerine fallback olur.
 */
@Service
@RequiredArgsConstructor
public class InvestingChartService {

    private static final Logger log = LoggerFactory.getLogger(InvestingChartService.class);

    private static final String SEARCH_TOPBAR_URL =
            "https://www.investing.com/search/service/searchTopBar";
    private static final String SEARCH_URL =
            "https://www.investing.com/search/service/search";

    /** sslcharts.investing.com günlük mum grafik, TR dili (force_lang=36). */
    private static final String CHART_URL_TEMPLATE =
            "https://sslcharts.investing.com/index.php"
                    + "?force_lang=36&pair_ID=%d&timescale=86400&candles=60&style=candles";

    private final StockRepository stockRepository;
    private final RestTemplate restTemplate;

    /** Var olan pair_ID'yi getirir; yoksa Investing'den arayıp cache'ler. */
    @Transactional
    public Long resolvePairId(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return null;
        String code = stockCode.trim().toUpperCase();

        Optional<Stock> opt = stockRepository.findByStockCode(code);
        if (opt.isEmpty()) return null;

        Stock stock = opt.get();
        if (stock.getInvestingPairId() != null) {
            return stock.getInvestingPairId();
        }

        Long pairId = lookupPairId(code);
        if (pairId != null) {
            stock.setInvestingPairId(pairId);
            stockRepository.save(stock);
            log.info("Investing pair_ID cache'lendi: {} → {}", code, pairId);
        } else {
            log.warn("Investing pair_ID bulunamadı: {}", code);
        }
        return pairId;
    }

    public String buildChartUrl(Long pairId) {
        if (pairId == null) return null;
        return String.format(CHART_URL_TEMPLATE, pairId);
    }

    /** Manuel atama — search bot korumasına takıldığında admin tarafından. */
    @Transactional
    public Stock setPairId(String stockCode, Long pairId) {
        Stock s = stockRepository.findByStockCode(stockCode.toUpperCase()).orElse(null);
        if (s == null) return null;
        s.setInvestingPairId(pairId);
        return stockRepository.save(s);
    }

    private Long lookupPairId(String code) {
        Long id = searchViaTopBar(code);
        if (id != null) return id;
        return searchViaSearch(code);
    }

    @SuppressWarnings("unchecked")
    private Long searchViaTopBar(String code) {
        try {
            HttpHeaders headers = browserHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("X-Requested-With", "XMLHttpRequest");

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("search_text", code);

            HttpEntity<MultiValueMap<String, String>> entity =
                    new HttpEntity<>(form, headers);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    SEARCH_TOPBAR_URL, HttpMethod.POST, entity, Map.class);

            return extractPairId(resp.getBody(), code, "topBar");
        } catch (Exception e) {
            log.warn("Investing searchTopBar hatası [{}]: {}", code, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Long searchViaSearch(String code) {
        try {
            HttpHeaders headers = browserHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            String url = SEARCH_URL + "?q=" + code;

            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return extractPairId(resp.getBody(), code, "search");
        } catch (Exception e) {
            log.warn("Investing search hatası [{}]: {}", code, e.getMessage());
            return null;
        }
    }

    private HttpHeaders browserHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/122.0.0.0 Safari/537.36");
        h.set("Accept", "application/json, text/javascript, */*; q=0.01");
        h.set("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8");
        h.set("Referer", "https://www.investing.com/");
        h.set("Origin", "https://www.investing.com");
        return h;
    }

    /**
     * Response'un birden fazla olası şekli var:
     *  - {"quotes": [...]}
     *  - {"All": [...]}
     *  - {"quotes": {"stocks": [...]}}
     *  - {"pairs": [...]}
     * Hepsindeki item'lardan BIST/Istanbul eşleşmesi aranır, bulunamazsa
     * sembol eşleşmesi.
     */
    @SuppressWarnings("unchecked")
    private Long extractPairId(Map<String, Object> body, String code, String src) {
        if (body == null) return null;

        List<Map<String, Object>> candidates = new ArrayList<>();
        collect(body.get("quotes"),   candidates);
        collect(body.get("All"),      candidates);
        collect(body.get("pairs"),    candidates);
        collect(body.get("stocks"),   candidates);

        if (candidates.isEmpty()) {
            log.warn("Investing [{}] yanıt içinde tanınan dizi yok ({}): {}",
                    src, code, body.keySet());
            return null;
        }

        Long istanbulMatch = findMatch(candidates, code, true);
        if (istanbulMatch != null) return istanbulMatch;
        return findMatch(candidates, code, false);
    }

    @SuppressWarnings("unchecked")
    private void collect(Object node, List<Map<String, Object>> out) {
        if (node instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        } else if (node instanceof Map<?, ?> m) {
            for (Object v : ((Map<String, Object>) m).values()) {
                collect(v, out);
            }
        }
    }

    private Long findMatch(List<Map<String, Object>> items, String code, boolean istanbulOnly) {
        for (Map<String, Object> q : items) {
            String symbol = firstStr(q, "symbol", "name", "pair_symbol", "Symbol");
            String exchange = firstStr(q, "exchange_name_short", "exchange", "exchange_name",
                    "exchange_trans", "flag");
            Object pairId = firstObj(q, "pair_ID", "pairId", "pair_id", "id");

            if (symbol == null || pairId == null) continue;
            if (!code.equalsIgnoreCase(symbol.trim())) continue;

            if (istanbulOnly) {
                String ex = exchange == null ? "" : exchange.toLowerCase();
                boolean isIstanbul = ex.contains("istanbul")
                        || ex.contains("bist")
                        || ex.contains("turkey")
                        || ex.equals("tr")
                        || ex.equals("is");
                if (!isIstanbul) continue;
            }

            try {
                return Long.parseLong(pairId.toString().replaceAll("\\D", ""));
            } catch (NumberFormatException ignore) { }
        }
        return null;
    }

    private static String firstStr(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v.toString();
        }
        return null;
    }

    private static Object firstObj(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }
}
