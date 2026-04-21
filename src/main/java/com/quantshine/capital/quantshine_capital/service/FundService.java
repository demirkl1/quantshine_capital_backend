package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.dto.AllocationItemDTO;
import com.quantshine.capital.quantshine_capital.dto.FundDetailDTO;
import com.quantshine.capital.quantshine_capital.dto.FundSummaryDTO;
import com.quantshine.capital.quantshine_capital.entity.*;
import com.quantshine.capital.quantshine_capital.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FundService {

    private static final Logger log = LoggerFactory.getLogger(FundService.class);

    private final FundRepository                  fundRepository;
    private final FundPriceHistoryRepository      historyRepository;
    private final FundStockHoldingRepository      fundStockHoldingRepository;
    private final FundCommodityHoldingRepository  fundCommodityHoldingRepository;
    private final InvestmentRepository            investmentRepository;
    private final UserRepository                  userRepository;
    private final CommodityService                commodityService;

    // ════════════════════════════════════════════════════════════
    //  Fiyat güncelleme
    // ════════════════════════════════════════════════════════════

    /**
     * Fiyatı günceller, geçmişe kaydeder ve ilgili cache'leri temizler.
     */
    @Transactional
    @CacheEvict(cacheNames = {"funds", "fundDetail", "advisorStats", "adminStats"}, allEntries = true)
    public void updatePriceAndLogHistory(String fundCode, BigDecimal newPrice) {
        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı!"));

        fund.setCurrentPrice(newPrice);
        fund.setLastUpdate(LocalDateTime.now());
        fundRepository.save(fund);

        historyRepository.save(FundPriceHistory.builder()
                .fundCode(fundCode.toUpperCase())
                .price(newPrice)
                .priceDate(LocalDateTime.now())
                .build());

        log.info("{} için yeni fiyat ({}) geçmişe kaydedildi.", fundCode, newPrice);
    }

    /**
     * Portföy değerine göre birim fiyatı yeniden hesaplar.
     * Formül: birimFiyat = (nakit + Σ hisse değeri) / Σ yatırımcı lotu
     */
    @Transactional
    @CacheEvict(cacheNames = {"funds", "fundDetail", "advisorStats", "adminStats"}, allEntries = true)
    public void updateFundPriceBasedOnPortfolio(String fundCode) {
        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı"));

        List<FundStockHolding>     stockHoldings     = fundStockHoldingRepository.findByFundCode(fundCode.toUpperCase());
        List<FundCommodityHolding> commodityHoldings = fundCommodityHoldingRepository.findByFundCode(fundCode.toUpperCase());

        BigDecimal totalValue = calcTotalPortfolioValue(fund, stockHoldings, commodityHoldings);

        if (totalValue.compareTo(BigDecimal.ZERO) == 0) {
            log.info("{} fonu için toplam portföy değeri sıfır — mevcut birim fiyat korunuyor: {}",
                    fundCode, fund.getCurrentPrice());
            return;
        }

        BigDecimal totalInvestorLots = investmentRepository.findByFundCode(fundCode.toUpperCase())
                .stream()
                .map(Investment::getLotCount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal unitPrice = totalInvestorLots.compareTo(BigDecimal.ZERO) > 0
                ? totalValue.divide(totalInvestorLots, 6, RoundingMode.HALF_UP)
                : totalValue;

        fund.setCurrentPrice(unitPrice);
        fund.setLastUpdate(LocalDateTime.now());
        fundRepository.save(fund);

        historyRepository.save(FundPriceHistory.builder()
                .fundCode(fundCode.toUpperCase())
                .price(unitPrice)
                .priceDate(LocalDateTime.now())
                .build());

        log.info("{} fonu güncellendi — toplam: {} TL, birim: {} TL, lot: {}",
                fundCode, totalValue, unitPrice, totalInvestorLots);
    }

    /** Tüm fonların birim fiyatını günceller. */
    @Transactional
    public void updateAllFundPrices() {
        List<Fund> allFunds = fundRepository.findAll();
        log.info("{} fon güncelleniyor...", allFunds.size());
        for (Fund fund : allFunds) {
            try {
                updateFundPriceBasedOnPortfolio(fund.getFundCode());
            } catch (Exception e) {
                log.error("{} fonu güncellenemedi: {}", fund.getFundCode(), e.getMessage());
            }
        }
        log.info("Tüm fon fiyatları güncellendi.");
    }

    /**
     * Her gün 18:35 (TR) — BIST kapanışı + piyasa snapshot sonrası —
     * tüm fonların birim fiyatını günceller ve geçmişe kaydeder.
     * Böylece fon grafiği zaman içinde gerçek bir seri oluşturur.
     */
    @Scheduled(cron = "0 35 18 * * *", zone = "Europe/Istanbul")
    public void dailyFundPriceSnapshot() {
        log.info("Günlük fon fiyat snapshot'ı başlıyor...");
        updateAllFundPrices();
    }

    // ════════════════════════════════════════════════════════════
    //  Fon silme
    // ════════════════════════════════════════════════════════════

    @Transactional
    @CacheEvict(cacheNames = {"funds", "fundDetail", "advisorStats", "adminStats"}, allEntries = true)
    public void deleteFund(String fundCode) {
        Fund fund = fundRepository.findByFundCode(fundCode)
                .orElseThrow(() -> new RuntimeException("'" + fundCode + "' kodlu fon bulunamadı."));

        if (userRepository.existsByManagedFundCode(fundCode)) {
            throw new RuntimeException(
                "Fona atanmış danışman var. Önce danışmanları başka bir fona aktarın.");
        }

        boolean hasActiveInvestors = investmentRepository.findByFundCode(fundCode)
                .stream()
                .anyMatch(i -> i.getLotCount() != null
                        && i.getLotCount().compareTo(BigDecimal.ZERO) > 0);
        if (hasActiveInvestors) {
            throw new RuntimeException(
                "Fonda aktif yatırımcı var. Önce tüm yatırımcı bakiyelerini sıfırlayın.");
        }

        fundStockHoldingRepository.deleteByFundCode(fundCode);
        fundCommodityHoldingRepository.deleteByFundCode(fundCode);
        fundRepository.delete(fund);
        log.info("Fon silindi: {}", fundCode);
    }

    // ════════════════════════════════════════════════════════════
    //  Genel API: liste ve detay DTO'ları
    // ════════════════════════════════════════════════════════════

    /**
     * Tüm fonları performans verileriyle birlikte döndürür.
     * 5 dakika cache'lenir — düşük değişim sıklıklı veriler.
     */
    @Transactional(readOnly = true)
    @Cacheable("funds")
    public List<FundSummaryDTO> getAllFundSummaries() {
        return fundRepository.findAll().stream()
                .map(this::toSummaryDTO)
                .collect(Collectors.toList());
    }

    /**
     * Tek bir fonun tam detayını döndürür.
     * 10 dakika cache'lenir; fund kodu key olarak kullanılır.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "fundDetail", key = "#fundCode.toUpperCase()")
    public Optional<FundDetailDTO> getFundDetail(String fundCode) {
        return fundRepository.findByFundCode(fundCode.toUpperCase())
                .map(this::toDetailDTO);
    }

    // ════════════════════════════════════════════════════════════
    //  Özel yardımcı metotlar
    // ════════════════════════════════════════════════════════════

    private FundSummaryDTO toSummaryDTO(Fund fund) {
        String code = fund.getFundCode();
        return new FundSummaryDTO(
                code,
                fund.getFundName(),
                fund.getFundType(),
                fund.getRiskLevel(),
                fund.getCurrentPrice(),
                calcReturn(code, fund.getCurrentPrice(), 1),
                calcReturn(code, fund.getCurrentPrice(), 30),
                calcReturn(code, fund.getCurrentPrice(), 90),
                calcReturn(code, fund.getCurrentPrice(), 180),
                calcYtdReturn(code, fund.getCurrentPrice()),
                calcReturn(code, fund.getCurrentPrice(), 365)
        );
    }

    /**
     * Detay DTO oluşturur — holdings ONCE yüklenir, hem değer hem dağılım için paylaşılır.
     * Önceki implementasyonda calcTotalPortfolioValue ve buildAllocation ayrı ayrı
     * repository çağrısı yapıyordu (2× fazla sorgu).
     */
    private FundDetailDTO toDetailDTO(Fund fund) {
        String code    = fund.getFundCode();
        BigDecimal cur = fund.getCurrentPrice();

        // Holdings TEK SEFERDE çekilir
        List<FundStockHolding>     stockHoldings     = fundStockHoldingRepository.findByFundCode(code);
        List<FundCommodityHolding> commodityHoldings = fundCommodityHoldingRepository.findByFundCode(code);

        BigDecimal total = calcTotalPortfolioValue(fund, stockHoldings, commodityHoldings);

        FundDetailDTO dto = new FundDetailDTO();
        dto.setCode(code);
        dto.setName(fund.getFundName());
        dto.setType(fund.getFundType());
        dto.setCurrency(fund.getCurrency());
        dto.setTefas(fund.getTefas());
        dto.setPrice(cur);
        dto.setTotalValue(total);

        BigDecimal totalLot = investmentRepository.findByFundCode(code).stream()
                .map(Investment::getLotCount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dto.setTotalLot(totalLot);

        dto.setInceptionDate(fund.getInceptionDate() != null
                ? fund.getInceptionDate().toString() : null);
        dto.setRiskLevel(fund.getRiskLevel());

        dto.setPerformance(new FundDetailDTO.PerformanceDTO(
                calcReturn(code, cur, 1),
                calcReturn(code, cur, 30),
                calcReturn(code, cur, 90),
                calcReturn(code, cur, 180),
                calcYtdReturn(code, cur),
                calcReturn(code, cur, 365)
        ));

        // Önceden yüklenen holdings paylaşılır — ekstra sorgu yok
        dto.setAllocation(buildAllocation(fund, total, stockHoldings, commodityHoldings));
        return dto;
    }

    private BigDecimal calcReturn(String fundCode, BigDecimal currentPrice, int daysAgo) {
        LocalDateTime pastDate = LocalDateTime.now().minusDays(daysAgo);
        return historyRepository
                .findFirstByFundCodeAndPriceDateLessThanEqualOrderByPriceDateDesc(fundCode, pastDate)
                .map(h -> percentage(currentPrice, h.getPrice()))
                .orElse(null);
    }

    private BigDecimal calcYtdReturn(String fundCode, BigDecimal currentPrice) {
        LocalDateTime startOfYear = LocalDate.now().withDayOfYear(1).atStartOfDay();
        return historyRepository
                .findFirstByFundCodeAndPriceDateLessThanEqualOrderByPriceDateDesc(fundCode, startOfYear)
                .map(h -> percentage(currentPrice, h.getPrice()))
                .orElse(null);
    }

    private BigDecimal percentage(BigDecimal current, BigDecimal past) {
        if (past == null || past.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(past)
                .divide(past, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Toplam portföy değeri hesaplar.
     * Holdings dışarıdan verilir — tekrar sorgu yapılmaz.
     * Emtia pozisyonları ANLIK piyasa değeriyle (lot × currentPrice × usdtryRate)
     * değerlenir; fiyat çekilememişse maliyet bedeline düşer.
     */
    public BigDecimal calcTotalPortfolioValue(Fund fund,
                                               List<FundStockHolding> stockHoldings,
                                               List<FundCommodityHolding> commodityHoldings) {
        BigDecimal total = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;

        for (FundStockHolding h : stockHoldings) {
            BigDecimal price = h.getStock().getCurrentPrice();
            if (price == null) continue;
            total = total.add(h.getLotCount().multiply(price));
        }

        BigDecimal usdTry = commodityService.getUsdtryRate();
        for (FundCommodityHolding h : commodityHoldings) {
            BigDecimal priceUsd = h.getCommodity() != null
                    ? h.getCommodity().getCurrentPrice() : null;
            if (priceUsd != null && h.getLotCount() != null && usdTry != null) {
                total = total.add(h.getLotCount().multiply(priceUsd).multiply(usdTry));
            } else if (h.getTotalCostTry() != null) {
                total = total.add(h.getTotalCostTry());
            }
        }

        return total;
    }

    /** Geriye uyumluluk için: holdings repo'dan yüklenir (tek fon işlemleri için). */
    public BigDecimal calcTotalPortfolioValue(Fund fund) {
        return calcTotalPortfolioValue(
            fund,
            fundStockHoldingRepository.findByFundCode(fund.getFundCode()),
            fundCommodityHoldingRepository.findByFundCode(fund.getFundCode())
        );
    }

    /**
     * Emtia pozisyonlarının ANLIK piyasa değerini TL cinsinden hesaplar.
     * Dashboard ve istatistik servisleri (TradeService) bu helper'ı kullanmalı —
     * maliyet bedeli (totalCostTry) zaman içinde gerçek değerden sapar.
     */
    public BigDecimal calcCommoditiesMarketValue(List<FundCommodityHolding> holdings) {
        if (holdings == null || holdings.isEmpty()) return BigDecimal.ZERO;
        BigDecimal usdTry = commodityService.getUsdtryRate();
        return holdings.stream()
                .map(h -> {
                    BigDecimal priceUsd = h.getCommodity() != null
                            ? h.getCommodity().getCurrentPrice() : null;
                    if (priceUsd != null && h.getLotCount() != null && usdTry != null) {
                        return h.getLotCount().multiply(priceUsd).multiply(usdTry);
                    }
                    return h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Emtia pozisyonlarının toplam maliyet bedeli (TL). K/Z hesabı için referans. */
    public BigDecimal calcCommoditiesCost(List<FundCommodityHolding> holdings) {
        if (holdings == null || holdings.isEmpty()) return BigDecimal.ZERO;
        return holdings.stream()
                .map(h -> h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Varlık dağılımı — holdings dışarıdan verilir, ekstra sorgu yok.
     */
    private List<AllocationItemDTO> buildAllocation(Fund fund, BigDecimal totalValue,
                                                     List<FundStockHolding> stockHoldings,
                                                     List<FundCommodityHolding> commodityHoldings) {
        List<AllocationItemDTO> items = new ArrayList<>();
        if (totalValue == null || totalValue.compareTo(BigDecimal.ZERO) <= 0) return items;

        BigDecimal stocksTotal = stockHoldings.stream()
                .map(h -> h.getLotCount().multiply(h.getStock().getCurrentPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (stocksTotal.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new AllocationItemDTO("Hisse Senedi", pct(stocksTotal, totalValue)));
        }

        BigDecimal usdTry = commodityService.getUsdtryRate();
        BigDecimal commoditiesTotal = commodityHoldings.stream()
                .map(h -> {
                    BigDecimal priceUsd = h.getCommodity() != null
                            ? h.getCommodity().getCurrentPrice() : null;
                    if (priceUsd != null && h.getLotCount() != null && usdTry != null) {
                        return h.getLotCount().multiply(priceUsd).multiply(usdTry);
                    }
                    return h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (commoditiesTotal.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new AllocationItemDTO("Emtia", pct(commoditiesTotal, totalValue)));
        }

        BigDecimal cash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
        if (cash.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new AllocationItemDTO("Nakit", pct(cash, totalValue)));
        }

        return items;
    }

    private BigDecimal pct(BigDecimal part, BigDecimal total) {
        return part.divide(total, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }
}
