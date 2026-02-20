package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.Commodity;
import com.quantshine.capital.quantshine_capital.entity.CommodityTradeHistory;
import com.quantshine.capital.quantshine_capital.entity.Fund;
import com.quantshine.capital.quantshine_capital.entity.FundCommodityHolding;
import com.quantshine.capital.quantshine_capital.repository.CommodityRepository;
import com.quantshine.capital.quantshine_capital.repository.CommodityTradeHistoryRepository;
import com.quantshine.capital.quantshine_capital.repository.FundCommodityHoldingRepository;
import com.quantshine.capital.quantshine_capital.repository.FundRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommodityService {

    private static final Logger log = LoggerFactory.getLogger(CommodityService.class);

    private final CommodityRepository commodityRepository;
    private final FundCommodityHoldingRepository fundCommodityHoldingRepository;
    private final CommodityTradeHistoryRepository commodityTradeHistoryRepository;
    private final FundRepository fundRepository;
    private final RestTemplate restTemplate;

    private static final String TV_URL = "https://scanner.tradingview.com/global/scan";

    // USD/TRY kuru (volatile: zamanlayıcı ile güncellenir)
    private volatile BigDecimal usdtryRate = BigDecimal.valueOf(38);

    // TradingView sembolü → Türkçe ad
    private static final Map<String, String> SYMBOL_NAMES = Map.of(
            "GC1!",  "Altın",
            "SI1!",  "Gümüş",
            "HG1!",  "Bakır",
            "UKOIL", "Brent Petrol",
            "NG1!",  "Doğalgaz",
            "PL1!",  "Platin"
    );

    public BigDecimal getUsdtryRate() {
        return usdtryRate;
    }

    /**
     * Uygulama açılışında ve her saat başında emtia + USDTRY fiyatlarını günceller.
     */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 5_000)
    @Transactional
    public void updateCommodityPrices() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Safari/537.36");
            headers.set("Origin",  "https://www.tradingview.com");
            headers.set("Referer", "https://www.tradingview.com/");

            String requestBody = """
                    {
                      "symbols": {
                        "tickers": [
                          "COMEX:GC1!", "COMEX:SI1!", "COMEX:HG1!",
                          "TVC:UKOIL",  "NYMEX:NG1!", "COMEX:PL1!",
                          "FX_IDC:USDTRY"
                        ]
                      },
                      "columns": ["name", "description", "close", "change", "change_abs"]
                    }
                    """;

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    TV_URL, HttpMethod.POST, entity, Map.class);

            if (response.getBody() == null) {
                log.warn("TradingView'dan emtia verisi gelmedi");
                return;
            }

            List<Map<String, Object>> dataList =
                    (List<Map<String, Object>>) response.getBody().get("data");
            if (dataList == null || dataList.isEmpty()) {
                log.warn("Emtia listesi boş");
                return;
            }

            int saved = 0;
            for (Map<String, Object> item : dataList) {
                try {
                    String fullSymbol = (String) item.get("s"); // COMEX:GC1!
                    List<Object> values = (List<Object>) item.get("d");
                    if (values == null || values.get(2) == null) continue;

                    // Kısa sembol: COMEX:GC1! → GC1!
                    String shortSymbol = fullSymbol.contains(":")
                            ? fullSymbol.split(":")[1]
                            : fullSymbol;

                    BigDecimal price = new BigDecimal(values.get(2).toString())
                            .setScale(4, RoundingMode.HALF_UP);

                    // USDTRY: sadece rate'i güncelle, DB'ye kaydetme
                    if ("USDTRY".equalsIgnoreCase(shortSymbol)) {
                        this.usdtryRate = price;
                        log.info("USD/TRY kuru güncellendi: {}", this.usdtryRate);
                        continue;
                    }

                    BigDecimal changePct = values.get(3) != null
                            ? new BigDecimal(values.get(3).toString()).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    BigDecimal changeAbs = values.get(4) != null
                            ? new BigDecimal(values.get(4).toString()).setScale(4, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    Commodity commodity = commodityRepository.findBySymbol(shortSymbol)
                            .orElse(new Commodity());
                    commodity.setSymbol(shortSymbol);
                    commodity.setNameTr(SYMBOL_NAMES.getOrDefault(shortSymbol, shortSymbol));
                    commodity.setCurrentPrice(price);
                    commodity.setChange(changeAbs);
                    commodity.setChangePercent(
                            (changePct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                            + changePct + "%");
                    commodity.setLastUpdate(LocalDateTime.now());
                    commodityRepository.save(commodity);
                    saved++;

                } catch (Exception e) {
                    log.warn("Emtia parse hatası: {}", e.getMessage());
                }
            }
            log.info("{} emtia fiyatı güncellendi.", saved);

        } catch (Exception e) {
            log.error("Emtia güncelleme hatası: {}", e.getMessage());
        }
    }

    public List<Commodity> getAllCommodities() {
        return commodityRepository.findAllByOrderByNameTrAsc();
    }

    /**
     * Emtia alım/satım işlemi.
     * totalAmountTry = lot × priceUsd × usdtryRate
     */
    @Transactional
    public String executeCommodityTrade(String fundCode, String symbol,
            BigDecimal lot, BigDecimal priceUsd, BigDecimal rate, String type) {

        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı: " + fundCode));

        Commodity commodity = commodityRepository.findBySymbol(symbol)
                .orElseThrow(() -> new RuntimeException("Emtia bulunamadı: " + symbol));

        BigDecimal totalAmountTry = lot.multiply(priceUsd).multiply(rate)
                .setScale(2, RoundingMode.HALF_UP);

        if ("BUY".equalsIgnoreCase(type)) {
            BigDecimal cashBalance = fund.getCashBalance() != null
                    ? fund.getCashBalance() : BigDecimal.ZERO;

            if (cashBalance.compareTo(totalAmountTry) < 0) {
                throw new RuntimeException(
                    "Yetersiz bakiye! Mevcut: " + cashBalance + " TRY, Gerekli: " + totalAmountTry + " TRY");
            }

            fund.setCashBalance(cashBalance.subtract(totalAmountTry));
            fundRepository.save(fund);

            FundCommodityHolding holding = fundCommodityHoldingRepository
                    .findByFundCodeAndCommodity_Symbol(fundCode.toUpperCase(), symbol)
                    .orElse(new FundCommodityHolding());

            if (holding.getId() == null) {
                holding.setFundCode(fundCode.toUpperCase());
                holding.setCommodity(commodity);
                holding.setLotCount(lot);
                holding.setAvgCostUsd(priceUsd);
                holding.setTotalCostTry(totalAmountTry);
                holding.setPurchaseDate(LocalDateTime.now());
            } else {
                BigDecimal oldTotalUsd = holding.getAvgCostUsd().multiply(holding.getLotCount());
                BigDecimal newTotalUsd = oldTotalUsd.add(lot.multiply(priceUsd));
                BigDecimal newLotCount = holding.getLotCount().add(lot);
                holding.setAvgCostUsd(newTotalUsd.divide(newLotCount, 4, RoundingMode.HALF_UP));
                holding.setLotCount(newLotCount);
                holding.setTotalCostTry(holding.getTotalCostTry().add(totalAmountTry));
            }
            fundCommodityHoldingRepository.save(holding);

            saveCommodityHistory(fundCode, symbol, commodity.getNameTr(),
                    "BUY", lot, priceUsd, rate, totalAmountTry);

            return "Alış işlemi başarılı: " + lot + " lot " + symbol + " @ $" + priceUsd;

        } else if ("SELL".equalsIgnoreCase(type)) {
            FundCommodityHolding holding = fundCommodityHoldingRepository
                    .findByFundCodeAndCommodity_Symbol(fundCode.toUpperCase(), symbol)
                    .orElseThrow(() -> new RuntimeException("Satılacak emtia pozisyonu bulunamadı: " + symbol));

            if (holding.getLotCount().compareTo(lot) < 0) {
                throw new RuntimeException(
                    "Yetersiz lot! Mevcut: " + holding.getLotCount() + ", Satılmak istenen: " + lot);
            }

            BigDecimal cashBalance = fund.getCashBalance() != null
                    ? fund.getCashBalance() : BigDecimal.ZERO;
            fund.setCashBalance(cashBalance.add(totalAmountTry));
            fundRepository.save(fund);

            BigDecimal remainingLot = holding.getLotCount().subtract(lot);
            if (remainingLot.compareTo(BigDecimal.ZERO) == 0) {
                fundCommodityHoldingRepository.delete(holding);
            } else {
                holding.setLotCount(remainingLot);
                holding.setTotalCostTry(
                    holding.getAvgCostUsd().multiply(remainingLot).multiply(rate)
                        .setScale(2, RoundingMode.HALF_UP));
                fundCommodityHoldingRepository.save(holding);
            }

            saveCommodityHistory(fundCode, symbol, commodity.getNameTr(),
                    "SELL", lot, priceUsd, rate, totalAmountTry);

            return "Satış işlemi başarılı: " + lot + " lot " + symbol + " @ $" + priceUsd;
        }

        throw new RuntimeException("Geçersiz işlem tipi: " + type);
    }

    private void saveCommodityHistory(String fundCode, String symbol, String nameTr,
            String type, BigDecimal lot, BigDecimal priceUsd, BigDecimal rate, BigDecimal totalAmountTry) {
        CommodityTradeHistory history = new CommodityTradeHistory();
        history.setFundCode(fundCode.toUpperCase());
        history.setSymbol(symbol);
        history.setNameTr(nameTr);
        history.setType(type);
        history.setLot(lot);
        history.setPriceUsd(priceUsd);
        history.setUsdtryRate(rate);
        history.setTotalAmountTry(totalAmountTry);
        history.setTradeDate(LocalDateTime.now());
        commodityTradeHistoryRepository.save(history);
    }

    /**
     * Belirli bir fonun emtia pozisyonlarını döner.
     * Her pozisyon için: symbol, nameTr, lot, avgCostUsd, currentPrice, totalCostTry, marketValueTry
     */
    public List<Map<String, Object>> getFundCommodityHoldings(String fundCode) {
        List<FundCommodityHolding> holdings =
                fundCommodityHoldingRepository.findByFundCode(fundCode.toUpperCase());

        List<Map<String, Object>> result = new ArrayList<>();
        for (FundCommodityHolding h : holdings) {
            Map<String, Object> map = new HashMap<>();
            map.put("symbol",       h.getCommodity().getSymbol());
            map.put("nameTr",       h.getCommodity().getNameTr());
            map.put("lot",          h.getLotCount());
            map.put("avgCostUsd",   h.getAvgCostUsd());
            map.put("currentPrice", h.getCommodity().getCurrentPrice());
            map.put("totalCostTry", h.getTotalCostTry());

            BigDecimal currentPrice = h.getCommodity().getCurrentPrice();
            if (currentPrice != null) {
                BigDecimal marketValue = h.getLotCount()
                        .multiply(currentPrice)
                        .multiply(this.usdtryRate)
                        .setScale(2, RoundingMode.HALF_UP);
                map.put("marketValueTry", marketValue);
            } else {
                map.put("marketValueTry", h.getTotalCostTry());
            }
            result.add(map);
        }
        return result;
    }

    public List<CommodityTradeHistory> getCommodityTradeHistory(String fundCode) {
        return commodityTradeHistoryRepository.findByFundCodeOrderByTradeDateDesc(fundCode);
    }

    public List<CommodityTradeHistory> getAllCommodityTradeHistory() {
        return commodityTradeHistoryRepository.findAllByOrderByTradeDateDesc();
    }
}
