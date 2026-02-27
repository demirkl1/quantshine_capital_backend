package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.dto.AllocationItemDTO;
import com.quantshine.capital.quantshine_capital.dto.FundDetailDTO;
import com.quantshine.capital.quantshine_capital.dto.FundSummaryDTO;
import com.quantshine.capital.quantshine_capital.entity.*;
import com.quantshine.capital.quantshine_capital.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    // ════════════════════════════════════════════════════════════
    //  Fiyat güncelleme (mevcut işlevler korundu)
    // ════════════════════════════════════════════════════════════

    /**
     * Fiyatı günceller ve geçmiş tablosuna kayıt atar.
     */
    @Transactional
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
    public void updateFundPriceBasedOnPortfolio(String fundCode) {
        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı"));

        BigDecimal totalValue = calcTotalPortfolioValue(fund);

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

    // ════════════════════════════════════════════════════════════
    //  Fon silme
    // ════════════════════════════════════════════════════════════

    /**
     * Fonu siler.
     * Ön koşullar:
     *   1. Fona atanmış aktif danışman olmamalı.
     *   2. Fonda lot > 0 olan aktif yatırımcı olmamalı.
     * İşlem geçmişi, fiyat geçmişi ve sıfır-lotlu investment kayıtları
     * tarihsel raporlama için korunur.
     */
    @Transactional
    public void deleteFund(String fundCode) {
        Fund fund = fundRepository.findByFundCode(fundCode)
                .orElseThrow(() -> new RuntimeException("'" + fundCode + "' kodlu fon bulunamadı."));

        // 1. Atanmış danışman kontrolü
        if (userRepository.existsByManagedFundCode(fundCode)) {
            throw new RuntimeException(
                "Fona atanmış danışman var. Önce danışmanları başka bir fona aktarın.");
        }

        // 2. Aktif yatırımcı kontrolü (lotCount > 0)
        boolean hasActiveInvestors = investmentRepository.findByFundCode(fundCode)
                .stream()
                .anyMatch(i -> i.getLotCount() != null
                        && i.getLotCount().compareTo(BigDecimal.ZERO) > 0);
        if (hasActiveInvestors) {
            throw new RuntimeException(
                "Fonda aktif yatırımcı var. Önce tüm yatırımcı bakiyelerini sıfırlayın.");
        }

        // 3. Aktif pozisyonları temizle (hisse & emtia)
        fundStockHoldingRepository.deleteByFundCode(fundCode);
        fundCommodityHoldingRepository.deleteByFundCode(fundCode);

        // 4. Fonu sil (işlem geçmişi, fiyat geçmişi ve investment kayıtları korunur)
        fundRepository.delete(fund);
        log.info("Fon silindi: {}", fundCode);
    }

    // ════════════════════════════════════════════════════════════
    //  Genel API: liste ve detay DTO'ları
    // ════════════════════════════════════════════════════════════

    /**
     * Tüm fonları performans verileriyle birlikte döndürür.
     * {@code GET /api/funds} endpoint'i için kullanılır.
     */
    @Transactional(readOnly = true)
    public List<FundSummaryDTO> getAllFundSummaries() {
        return fundRepository.findAll().stream()
                .map(this::toSummaryDTO)
                .collect(Collectors.toList());
    }

    /**
     * Tek bir fonun tam detayını (performans + varlık dağılımı) döndürür.
     * {@code GET /api/funds/{code}} endpoint'i için kullanılır.
     */
    @Transactional(readOnly = true)
    public Optional<FundDetailDTO> getFundDetail(String fundCode) {
        return fundRepository.findByFundCode(fundCode.toUpperCase())
                .map(this::toDetailDTO);
    }

    // ════════════════════════════════════════════════════════════
    //  Özel yardımcı metotlar
    // ════════════════════════════════════════════════════════════

    /** Fund → FundSummaryDTO dönüşümü */
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

    /** Fund → FundDetailDTO dönüşümü */
    private FundDetailDTO toDetailDTO(Fund fund) {
        String code        = fund.getFundCode();
        BigDecimal current = fund.getCurrentPrice();
        BigDecimal total   = calcTotalPortfolioValue(fund);

        FundDetailDTO dto = new FundDetailDTO();
        dto.setCode(code);
        dto.setName(fund.getFundName());
        dto.setType(fund.getFundType());
        dto.setCurrency(fund.getCurrency());
        dto.setTefas(fund.getTefas());
        dto.setPrice(current);
        dto.setTotalValue(total);
        dto.setInceptionDate(fund.getInceptionDate() != null
                ? fund.getInceptionDate().toString() : null);
        dto.setRiskLevel(fund.getRiskLevel());

        dto.setPerformance(new FundDetailDTO.PerformanceDTO(
                calcReturn(code, current, 1),
                calcReturn(code, current, 30),
                calcReturn(code, current, 90),
                calcReturn(code, current, 180),
                calcYtdReturn(code, current),
                calcReturn(code, current, 365)
        ));

        dto.setAllocation(buildAllocation(fund, total));
        return dto;
    }

    /**
     * Dönemlik getiri yüzdesi hesaplar.
     * Formül: ((güncelFiyat - geçmişFiyat) / geçmişFiyat) × 100
     *
     * @param daysAgo kaç gün öncesi referans alınsın
     * @return getiri yüzdesi (2 ondalık); geçmiş veri yoksa null
     */
    private BigDecimal calcReturn(String fundCode, BigDecimal currentPrice, int daysAgo) {
        LocalDateTime pastDate = LocalDateTime.now().minusDays(daysAgo);
        return historyRepository
                .findFirstByFundCodeAndPriceDateLessThanEqualOrderByPriceDateDesc(fundCode, pastDate)
                .map(h -> percentage(currentPrice, h.getPrice()))
                .orElse(null);
    }

    /**
     * Yılbaşından bugüne getiri hesaplar.
     * 1 Ocak'taki (veya sonraki ilk) birim fiyatı baz alır.
     */
    private BigDecimal calcYtdReturn(String fundCode, BigDecimal currentPrice) {
        LocalDateTime startOfYear = LocalDate.now().withDayOfYear(1).atStartOfDay();
        return historyRepository
                .findFirstByFundCodeAndPriceDateLessThanEqualOrderByPriceDateDesc(fundCode, startOfYear)
                .map(h -> percentage(currentPrice, h.getPrice()))
                .orElse(null);
    }

    /**
     * Yüzde hesabı: ((yeni - eski) / eski) × 100, 2 ondalık.
     * Payda sıfırsa null döner.
     */
    private BigDecimal percentage(BigDecimal current, BigDecimal past) {
        if (past == null || past.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(past)
                .divide(past, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Toplam portföy değeri = nakit + Σ(hisse lotu × güncel fiyat) + Σ(emtia maliyet TRY).
     * Not: Emtia için güncel kur yerine maliyet bedeli kullanılır; ileride
     * commodity.currentPriceTry alanı eklenirse güncellenmesi önerilir.
     */
    private BigDecimal calcTotalPortfolioValue(Fund fund) {
        BigDecimal total = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;

        // Hisse senedi değerleri
        for (FundStockHolding h : fundStockHoldingRepository.findByFundCode(fund.getFundCode())) {
            total = total.add(h.getLotCount().multiply(h.getStock().getCurrentPrice()));
        }

        // Emtia maliyet değerleri (TRY bazlı)
        for (FundCommodityHolding h : fundCommodityHoldingRepository.findByFundCode(fund.getFundCode())) {
            if (h.getTotalCostTry() != null) {
                total = total.add(h.getTotalCostTry());
            }
        }

        return total;
    }

    /**
     * Varlık dağılımı listesi oluşturur.
     * Şirket/emtia isimleri dışarıya açılmaz; yalnızca üç genel kategori döner:
     *   • Hisse Senedi  – fondaki tüm hisse pozisyonlarının toplamı
     *   • Emtia         – fondaki tüm emtia pozisyonlarının toplamı
     *   • Nakit         – fon nakit bakiyesi
     */
    private List<AllocationItemDTO> buildAllocation(Fund fund, BigDecimal totalValue) {
        List<AllocationItemDTO> items = new ArrayList<>();
        if (totalValue == null || totalValue.compareTo(BigDecimal.ZERO) <= 0) return items;

        String code = fund.getFundCode();

        // ── Hisse Senetleri (toplam) ──────────────────────────────
        BigDecimal stocksTotal = BigDecimal.ZERO;
        for (FundStockHolding h : fundStockHoldingRepository.findByFundCode(code)) {
            stocksTotal = stocksTotal.add(h.getLotCount().multiply(h.getStock().getCurrentPrice()));
        }
        if (stocksTotal.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new AllocationItemDTO("Hisse Senedi", pct(stocksTotal, totalValue)));
        }

        // ── Emtialar (toplam, TRY maliyet bazlı) ─────────────────
        BigDecimal commoditiesTotal = BigDecimal.ZERO;
        for (FundCommodityHolding h : fundCommodityHoldingRepository.findByFundCode(code)) {
            if (h.getTotalCostTry() != null) {
                commoditiesTotal = commoditiesTotal.add(h.getTotalCostTry());
            }
        }
        if (commoditiesTotal.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new AllocationItemDTO("Emtia", pct(commoditiesTotal, totalValue)));
        }

        // ── Nakit ────────────────────────────────────────────────
        BigDecimal cash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
        if (cash.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new AllocationItemDTO("Nakit", pct(cash, totalValue)));
        }

        return items;
    }

    /** Yüzde hesabı: (parça / toplam) × 100, 1 ondalık */
    private BigDecimal pct(BigDecimal part, BigDecimal total) {
        return part.divide(total, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }
}
