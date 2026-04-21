package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.*;
import com.quantshine.capital.quantshine_capital.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.quantshine.capital.quantshine_capital.dto.DashboardStatsDTO;
import com.quantshine.capital.quantshine_capital.dto.AdvisorStatsDTO;
import com.quantshine.capital.quantshine_capital.entity.Role;
import com.quantshine.capital.quantshine_capital.dto.InvestorPortfolioDTO;
import com.quantshine.capital.quantshine_capital.entity.Fund;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TransactionRepository transactionRepository;
    private final InvestmentRepository investmentRepository;
    private final UserRepository userRepository;
    private final FundRepository fundRepository;
    private final FundStockHoldingRepository fundStockHoldingRepository;
    private final FundCommodityHoldingRepository fundCommodityHoldingRepository;
    private final FundService fundService;

    @Transactional
    @CacheEvict(cacheNames = {"adminStats", "advisorStats", "investorFunds"}, allEntries = true)
    public void executeTrade(String advisorKeycloakId, String investorTc, String fundCode,
                             BigDecimal amount, TransactionType type) {

        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı: " + fundCode));

        BigDecimal actualPrice = fund.getCurrentPrice();

        User investor = userRepository.findByTcNo(investorTc)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı: " + investorTc));

        User advisor = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException(
                        "İşlemi yapan kullanıcı sistemde bulunamadı (keycloakId=" + advisorKeycloakId + "). Lütfen tekrar giriş yapın."));

        // Yatırım kaydı yoksa BUY durumunda giriş yapan yetkili (ADMIN/ADVISOR) advisor
        // olarak atanır ve kayıt otomatik açılır. SELL için mevcut kayıt zorunludur.
        Investment invRecord = investmentRepository
                .findByInvestorIdAndFundCode(investor.getId(), fundCode.toUpperCase())
                .orElseGet(() -> {
                    if (type != TransactionType.BUY) {
                        throw new RuntimeException(
                                "Bu fon (" + fundCode + ") için yatırım kaydı bulunamadı!");
                    }
                    Investment fresh = new Investment();
                    fresh.setInvestor(investor);
                    fresh.setAdvisor(advisor);
                    fresh.setFundCode(fundCode.toUpperCase());
                    fresh.setBalance(BigDecimal.ZERO);
                    fresh.setLotCount(BigDecimal.ZERO);
                    return investmentRepository.save(fresh);
                });

        BigDecimal lotCount = amount.divide(actualPrice, 4, RoundingMode.HALF_UP);
        BigDecimal fundCash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;

        if (type == TransactionType.BUY) {
            invRecord.setBalance(invRecord.getBalance().add(amount));
            invRecord.setLotCount(invRecord.getLotCount().add(lotCount));
            fund.setCashBalance(fundCash.add(amount));
            fundRepository.save(fund);
        } else if (type == TransactionType.SELL) {
            if (invRecord.getLotCount().compareTo(lotCount) < 0) {
                throw new RuntimeException("Yetersiz lot! Mevcut lot: " + invRecord.getLotCount());
            }
            invRecord.setBalance(invRecord.getBalance().subtract(amount));
            invRecord.setLotCount(invRecord.getLotCount().subtract(lotCount));
            fund.setCashBalance(fundCash.subtract(amount).max(BigDecimal.ZERO));
            fundRepository.save(fund);
        }

        Transaction transaction = new Transaction();
        transaction.setInvestor(investor);
        transaction.setAdvisor(advisor);
        transaction.setFundCode(fundCode.toUpperCase());
        transaction.setType(type);
        transaction.setAmount(amount);
        transaction.setLotCount(lotCount);
        transaction.setUnitPrice(actualPrice);

        investmentRepository.save(invRecord);
        transactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public List<Transaction> getInvestorHistoryByKeycloakId(String keycloakId) {
        User investor = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı"));
        return transactionRepository.findByInvestorIdOrderByCreatedAtDesc(investor.getId());
    }

    /**
     * Tüm işlem geçmişi — investor/advisor JOIN FETCH ile N+1 önlendi.
     * Fon fiyatları tek sorguda map'e alınır.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllHistoryEnriched() {
        // JOIN FETCH ile hem transaction hem investor/advisor tek sorguda
        List<Transaction> transactions = transactionRepository.findAllByOrderByCreatedAtDesc();

        // Tüm fon fiyatları TEK sorguda — döngü içinde findByFundCode yok
        Map<String, BigDecimal> currentPrices = fundRepository.findAll().stream()
                .collect(Collectors.toMap(Fund::getFundCode, Fund::getCurrentPrice));

        return transactions.stream().map(t -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("investorTc",   t.getInvestor() != null ? t.getInvestor().getTcNo() : "-");
            map.put("investorName", t.getInvestor() != null ?
                    t.getInvestor().getFirstName() + " " + t.getInvestor().getLastName() : "Bilinmiyor");
            map.put("fundCode",     t.getFundCode());
            map.put("amount",       t.getAmount());
            map.put("lot",          t.getLotCount());
            map.put("type",         t.getType());
            map.put("createdAt",    t.getCreatedAt());
            map.put("boughtPrice",  t.getUnitPrice());
            map.put("currentPrice", currentPrices.getOrDefault(t.getFundCode(), BigDecimal.ONE));
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * Admin istatistikleri — tüm fonların holdings'i TEK sorguda çekilir.
     * Önceki implementasyon her fon için ayrı N+1 sorgu yapıyordu.
     */
    @Transactional(readOnly = true)
    @Cacheable("adminStats")
    public DashboardStatsDTO getAdminStats(String advisorKeycloakId) {
        User admin = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yetkili bulunamadı"));

        List<Fund> allFunds = fundRepository.findAll();

        // Tüm holdings TEK JOIN sorgusunda — N+1 ortadan kalkar
        Map<String, List<FundStockHolding>> holdingsByFund = fundStockHoldingRepository
                .findAllWithStock()
                .stream()
                .collect(Collectors.groupingBy(h -> h.getFundCode().toUpperCase()));

        Map<String, List<FundCommodityHolding>> commoditiesByFund = fundCommodityHoldingRepository
                .findAll()
                .stream()
                .collect(Collectors.groupingBy(h -> h.getFundCode().toUpperCase()));

        // Tüm investment balance'ları TEK sorguda
        Map<String, BigDecimal> investedByFund = investmentRepository.findAll()
                .stream()
                .collect(Collectors.groupingBy(
                        inv -> inv.getFundCode().toUpperCase(),
                        Collectors.reducing(BigDecimal.ZERO,
                                inv -> inv.getBalance() != null ? inv.getBalance() : BigDecimal.ZERO,
                                BigDecimal::add)
                ));

        BigDecimal sirketFonBuyuklugu = BigDecimal.ZERO;
        BigDecimal sirketKarZarar     = BigDecimal.ZERO;
        BigDecimal fonBuyuklugu       = BigDecimal.ZERO;
        BigDecimal fonKarZarar        = BigDecimal.ZERO;

        for (Fund fund : allFunds) {
            String code = fund.getFundCode().toUpperCase();

            List<FundStockHolding> holdings = holdingsByFund.getOrDefault(code, List.of());
            BigDecimal stocksCurrent = holdings.stream()
                    .map(h -> h.getStock().getCurrentPrice().multiply(h.getLotCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal stocksCost = holdings.stream()
                    .map(h -> h.getAvgCost().multiply(h.getLotCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<FundCommodityHolding> commHoldings = commoditiesByFund.getOrDefault(code, List.of());
            BigDecimal commoditiesMarket = fundService.calcCommoditiesMarketValue(commHoldings);
            BigDecimal commoditiesCost   = fundService.calcCommoditiesCost(commHoldings);

            BigDecimal cash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
            if (cash.compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal totalInvested = investedByFund.getOrDefault(code, BigDecimal.ZERO);
                cash = totalInvested.subtract(stocksCost).subtract(commoditiesCost).max(BigDecimal.ZERO);
            }

            BigDecimal totalValue = cash.add(stocksCurrent).add(commoditiesMarket);
            BigDecimal kz         = stocksCurrent.subtract(stocksCost)
                                                 .add(commoditiesMarket.subtract(commoditiesCost));

            sirketFonBuyuklugu = sirketFonBuyuklugu.add(totalValue);
            sirketKarZarar     = sirketKarZarar.add(kz);

            if (fund.getFundCode().equalsIgnoreCase(admin.getManagedFundCode())) {
                fonBuyuklugu = totalValue;
                fonKarZarar  = kz;
            }
        }

        return new DashboardStatsDTO(sirketFonBuyuklugu, sirketKarZarar, fonBuyuklugu, fonKarZarar);
    }

    /**
     * Danışman performans listesi — in-memory filter yerine DB query.
     * Önceki: findAll().filter(role) → Şimdi: findAllByRoleIn() ile doğrudan DB sorgusu.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllAdvisorsWithLivePerformance() {
        // Sadece ADVISOR ve ADMIN kullanıcıları DB'den çek
        List<User> advisors = userRepository.findAllByRoleIn(List.of(Role.ADVISOR, Role.ADMIN));

        // Tüm fon fiyatları tek sorguda
        Map<String, BigDecimal> currentPrices = fundRepository.findAll().stream()
                .collect(Collectors.toMap(Fund::getFundCode, Fund::getCurrentPrice));

        // Tüm investment'lar tek sorguda — sonra fund koduna göre grupla
        Map<String, List<Investment>> invsByFund = investmentRepository.findAll().stream()
                .collect(Collectors.groupingBy(inv -> inv.getFundCode().toUpperCase()));

        return advisors.stream()
                .map(advisor -> {
                    String managedFund = advisor.getManagedFundCode();
                    BigDecimal currentPrice = managedFund != null
                            ? currentPrices.getOrDefault(managedFund, BigDecimal.ONE)
                            : BigDecimal.ONE;

                    List<Investment> fundInvs = managedFund != null
                            ? invsByFund.getOrDefault(managedFund.toUpperCase(), List.of())
                            : List.of();

                    BigDecimal toplamYatirim = BigDecimal.ZERO;
                    BigDecimal guncelDeger   = BigDecimal.ZERO;

                    for (Investment inv : fundInvs) {
                        toplamYatirim = toplamYatirim.add(inv.getBalance());
                        guncelDeger   = guncelDeger.add(inv.getLotCount().multiply(currentPrice));
                    }

                    BigDecimal karZararTl = guncelDeger.subtract(toplamYatirim);
                    String karZararYuzde = "0.00";

                    if (toplamYatirim.compareTo(BigDecimal.ZERO) > 0) {
                        karZararYuzde = karZararTl.divide(toplamYatirim, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP).toString();
                    }

                    Map<String, Object> map = new HashMap<>();
                    map.put("tcNo",        advisor.getTcNo());
                    map.put("fullName",    advisor.getFirstName() + " " + advisor.getLastName());
                    map.put("email",       advisor.getEmail());
                    map.put("phone",       advisor.getPhoneNumber());
                    map.put("managedFund", managedFund);
                    map.put("performance", karZararYuzde);
                    return map;
                }).collect(Collectors.toList());
    }

    /**
     * Yatırımcı portföyü — fund fiyatları tek sorguda map'e alınır.
     * Önceki: döngü içinde fundRepository.findByFundCode (N+1) → Şimdi: tek sorgu.
     */
    @Transactional(readOnly = true)
    public InvestorPortfolioDTO getInvestorPortfolio(String investorKeycloakId, String fundCode) {
        User investor = userRepository.findByKeycloakId(investorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));

        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı"));

        Investment inv = investmentRepository
                .findByInvestorIdAndFundCode(investor.getId(), fundCode.toUpperCase())
                .orElse(new Investment());

        BigDecimal toplamLot     = inv.getLotCount() != null ? inv.getLotCount() : BigDecimal.ZERO;
        BigDecimal yatirilanPara = inv.getBalance()  != null ? inv.getBalance()  : BigDecimal.ZERO;
        BigDecimal guncelDeger   = toplamLot.multiply(fund.getCurrentPrice());
        BigDecimal karZararTl    = guncelDeger.subtract(yatirilanPara);

        String karZararYuzde = yatirilanPara.compareTo(BigDecimal.ZERO) > 0
                ? karZararTl.multiply(new BigDecimal("100"))
                        .divide(yatirilanPara, 2, RoundingMode.HALF_UP).toString()
                : "0.00";

        List<Investment> allInvs = investmentRepository.findByInvestorId(investor.getId());

        // Tüm fund kodları için tek sorguda fiyat al — döngü içinde N sorgu yok
        Map<String, BigDecimal> pricesByCode = fundRepository.findAll().stream()
                .collect(Collectors.toMap(
                        f -> f.getFundCode().toUpperCase(),
                        Fund::getCurrentPrice
                ));

        BigDecimal toplamPortfoyBuyuklugu = allInvs.stream()
                .map(i -> i.getLotCount().multiply(
                        pricesByCode.getOrDefault(i.getFundCode().toUpperCase(), BigDecimal.ONE)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal toplamMaliyet = allInvs.stream()
                .map(i -> i.getBalance() != null ? i.getBalance() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal genelKarTl = toplamPortfoyBuyuklugu.subtract(toplamMaliyet);
        String genelKarYuzde = toplamMaliyet.compareTo(BigDecimal.ZERO) > 0
                ? genelKarTl.multiply(new BigDecimal("100"))
                        .divide(toplamMaliyet, 2, RoundingMode.HALF_UP).toString()
                : "0.00";

        return new InvestorPortfolioDTO(
                toplamLot, karZararTl, karZararYuzde,
                guncelDeger, toplamPortfoyBuyuklugu, genelKarYuzde);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getEnrichedInvestorHistory(String keycloakId) {
        List<Transaction> transactions = getInvestorHistoryByKeycloakId(keycloakId);

        Map<String, BigDecimal> currentPrices = fundRepository.findAll().stream()
                .collect(Collectors.toMap(Fund::getFundCode, Fund::getCurrentPrice));

        return transactions.stream().map(t -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id",           t.getId());
            map.put("createdAt",    t.getCreatedAt());
            map.put("fundCode",     t.getFundCode());
            map.put("type",         t.getType());
            map.put("lotCount",     t.getLotCount());
            map.put("amount",       t.getAmount());
            map.put("unitPrice",    t.getUnitPrice());
            map.put("currentPrice", currentPrices.getOrDefault(t.getFundCode(), BigDecimal.ONE));
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * Danışman istatistikleri — 10 dakika cache'lenir.
     * Holdings tek sorguda JOIN FETCH ile yüklenir.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "advisorStats", key = "#advisorKeycloakId")
    public AdvisorStatsDTO getAdvisorStats(String advisorKeycloakId) {
        User advisor = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Danışman bulunamadı"));

        String managedFundCode = advisor.getManagedFundCode();
        if (managedFundCode == null) return new AdvisorStatsDTO(BigDecimal.ZERO, BigDecimal.ZERO, "0.00");

        Fund fund = fundRepository.findByFundCode(managedFundCode).orElse(null);
        if (fund == null) return new AdvisorStatsDTO(BigDecimal.ZERO, BigDecimal.ZERO, "0.00");

        List<FundStockHolding> holdings = fundStockHoldingRepository.findByFundCode(managedFundCode.toUpperCase());
        BigDecimal stocksCurrentValue = holdings.stream()
                .map(h -> h.getStock().getCurrentPrice().multiply(h.getLotCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stocksCostValue = holdings.stream()
                .map(h -> h.getAvgCost().multiply(h.getLotCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<FundCommodityHolding> commHoldings = fundCommodityHoldingRepository
                .findByFundCode(managedFundCode.toUpperCase());
        BigDecimal commoditiesMarket = fundService.calcCommoditiesMarketValue(commHoldings);
        BigDecimal commoditiesCost   = fundService.calcCommoditiesCost(commHoldings);

        BigDecimal cashBalance = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
        if (cashBalance.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal totalInvested = investmentRepository.findByFundCode(managedFundCode)
                    .stream()
                    .map(inv -> inv.getBalance() != null ? inv.getBalance() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cashBalance = totalInvested.subtract(stocksCostValue).subtract(commoditiesCost).max(BigDecimal.ZERO);
        }

        BigDecimal fonBuyuklugu = cashBalance.add(stocksCurrentValue).add(commoditiesMarket);
        BigDecimal karZararTl   = stocksCurrentValue.subtract(stocksCostValue)
                                                     .add(commoditiesMarket.subtract(commoditiesCost));
        BigDecimal totalCost    = stocksCostValue.add(commoditiesCost);
        String karZararYuzde = totalCost.compareTo(BigDecimal.ZERO) > 0
                ? karZararTl.divide(totalCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toString()
                : "0.00";

        return new AdvisorStatsDTO(fonBuyuklugu, karZararTl, karZararYuzde);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllAdvisorsWithStats() {
        List<User> advisors = userRepository.findAllByRoleIn(List.of(Role.ADMIN, Role.ADVISOR));

        return advisors.stream()
                .map(u -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id",              u.getId());
                    map.put("tcNo",            u.getTcNo());
                    map.put("firstName",       u.getFirstName());
                    map.put("lastName",        u.getLastName());
                    map.put("fullName",        u.getFirstName() + " " + u.getLastName());
                    map.put("email",           u.getEmail());
                    map.put("phone",           u.getPhoneNumber());
                    map.put("managedFundCode", u.getManagedFundCode());
                    map.put("managedFund",     u.getManagedFundCode());
                    map.put("role",            u.getRole().name());
                    map.put("description",     u.getDescription());

                    if (u.getManagedFundCode() != null) {
                        try {
                            AdvisorStatsDTO stats = getAdvisorStats(u.getKeycloakId());
                            map.put("performance", stats.getFonKarZararYuzde());
                        } catch (Exception e) {
                            map.put("performance", "0.00");
                        }
                    } else {
                        map.put("performance", "0.00");
                    }

                    return map;
                }).collect(Collectors.toList());
    }

    /**
     * Tüm yatırımcılar ve portföyleri — DB'den doğru filtreleme, in-memory filter yok.
     * Önceki: findAll().stream().filter(role == INVESTOR) → Şimdi: findByRoleAndIsApprovedTrue.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllInvestorsWithDetailedPortfolios() {
        List<User> investors = userRepository.findByRoleAndIsApprovedTrue(Role.INVESTOR);

        Map<String, BigDecimal> currentPrices = fundRepository.findAll().stream()
                .collect(Collectors.toMap(
                        f -> f.getFundCode().toUpperCase(),
                        Fund::getCurrentPrice
                ));

        // Tüm investment'lar tek sorguda — döngü içinde N+1 yok
        Map<Long, List<Investment>> invsByInvestor = investmentRepository.findAll().stream()
                .collect(Collectors.groupingBy(inv -> inv.getInvestor().getId()));

        return investors.stream().map(investor -> {
            Map<String, Object> map = new HashMap<>();
            map.put("tcNo",     investor.getTcNo());
            map.put("fullName", investor.getFirstName() + " " + investor.getLastName());
            map.put("email",    investor.getEmail());

            List<Investment> myInvestments = invsByInvestor.getOrDefault(investor.getId(), List.of());

            List<Map<String, Object>> holdings = myInvestments.stream().map(inv -> {
                BigDecimal price   = currentPrices.getOrDefault(inv.getFundCode().toUpperCase(), BigDecimal.ONE);
                BigDecimal tlValue = inv.getLotCount().multiply(price);

                Map<String, Object> h = new HashMap<>();
                h.put("fundCode", inv.getFundCode());
                h.put("lots",     inv.getLotCount());
                h.put("tlValue",  tlValue);
                return h;
            }).collect(Collectors.toList());

            map.put("holdings", holdings);

            BigDecimal totalValue = holdings.stream()
                    .map(h -> (BigDecimal) h.get("tlValue"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            map.put("totalPortfolioValue", totalValue);

            return map;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<String> getInvestorFunds(String keycloakId) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı"));

        return investmentRepository.findByInvestorId(user.getId())
                .stream()
                .map(inv -> inv.getFundCode().toUpperCase())
                .distinct()
                .collect(Collectors.toList());
    }
}
