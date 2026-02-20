package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.*;
import com.quantshine.capital.quantshine_capital.repository.*;
import lombok.RequiredArgsConstructor;
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

    /**
     * @param advisorKeycloakId İşlemi yapan danışmanın Keycloak ID'si
     * @param investorTc        Yatırımcının TC kimlik numarası
     * @param fundCode          İşlem yapılan fon kodu
     * @param amount            İşlem tutarı (TL)
     * @param type              BUY (Alım) veya SELL (Satım)
     */
    @Transactional
    public void executeTrade(String advisorKeycloakId, String investorTc, String fundCode, BigDecimal amount, TransactionType type) {

        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı: " + fundCode));

        BigDecimal actualPrice = fund.getCurrentPrice();

        User investor = userRepository.findByTcNo(investorTc)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı: " + investorTc));

        User advisor = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yetkili (Danışman) bulunamadı!"));

        Investment invRecord = investmentRepository.findByInvestorId(investor.getId())
                .stream()
                .filter(i -> i.getFundCode().equalsIgnoreCase(fundCode))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Bu fon (" + fundCode + ") için yatırım kaydı bulunamadı!"));

        // Lot hesabı: işlem tutarı / gerçek birim fiyat
        BigDecimal lotCount = amount.divide(actualPrice, 4, RoundingMode.HALF_UP);

        BigDecimal fundCash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;

        if (type == TransactionType.BUY) {
            invRecord.setBalance(invRecord.getBalance().add(amount));
            invRecord.setLotCount(invRecord.getLotCount().add(lotCount));
            // Yatırımcının yatırdığı para fonun nakit bakiyesine eklenir
            fund.setCashBalance(fundCash.add(amount));
            fundRepository.save(fund);
        } else if (type == TransactionType.SELL) {
            if (invRecord.getLotCount().compareTo(lotCount) < 0) {
                throw new RuntimeException("Yetersiz lot! Mevcut lot: " + invRecord.getLotCount());
            }
            invRecord.setBalance(invRecord.getBalance().subtract(amount));
            invRecord.setLotCount(invRecord.getLotCount().subtract(lotCount));
            // Yatırımcıya ödenen para fonun nakit bakiyesinden düşülür
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

    public List<Transaction> getInvestorHistoryByKeycloakId(String keycloakId) {
        User investor = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new RuntimeException("Kullanıcı bulunamadı"));
        return transactionRepository.findByInvestorIdOrderByCreatedAtDesc(investor.getId());
    }

    public List<Map<String, Object>> getAllHistoryEnriched() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByCreatedAtDesc();

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

            BigDecimal livePrice = currentPrices.getOrDefault(t.getFundCode(), BigDecimal.ONE);
            map.put("currentPrice", livePrice);

            return map;
        }).collect(Collectors.toList());
    }

    public DashboardStatsDTO getAdminStats(String advisorKeycloakId) {
        User admin = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yetkili bulunamadı"));

        List<Fund> allFunds = fundRepository.findAll();

        // Şirket toplamı: tüm fonların gerçek portföy değeri (nakit + hisseler)
        BigDecimal sirketFonBuyuklugu = BigDecimal.ZERO;
        BigDecimal sirketKarZarar     = BigDecimal.ZERO;
        BigDecimal fonBuyuklugu       = BigDecimal.ZERO;
        BigDecimal fonKarZarar        = BigDecimal.ZERO;

        for (Fund fund : allFunds) {
            List<FundStockHolding> holdings = fundStockHoldingRepository
                    .findByFundCode(fund.getFundCode().toUpperCase());
            BigDecimal stocksCurrent = holdings.stream()
                    .map(h -> h.getStock().getCurrentPrice().multiply(h.getLotCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal stocksCost = holdings.stream()
                    .map(h -> h.getAvgCost().multiply(h.getLotCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal commoditiesValue = fundCommodityHoldingRepository
                    .findByFundCode(fund.getFundCode().toUpperCase()).stream()
                    .map(h -> h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // cashBalance 0 ise yatırımcı toplamından hisse ve emtia maliyetini düşerek türet
            BigDecimal cash = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
            if (cash.compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal totalInvested = investmentRepository.findByFundCode(fund.getFundCode())
                        .stream()
                        .map(inv -> inv.getBalance() != null ? inv.getBalance() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                cash = totalInvested.subtract(stocksCost).subtract(commoditiesValue).max(BigDecimal.ZERO);
            }

            BigDecimal totalValue = cash.add(stocksCurrent).add(commoditiesValue);
            BigDecimal kz         = stocksCurrent.subtract(stocksCost);

            sirketFonBuyuklugu = sirketFonBuyuklugu.add(totalValue);
            sirketKarZarar     = sirketKarZarar.add(kz);

            if (fund.getFundCode().equalsIgnoreCase(admin.getManagedFundCode())) {
                fonBuyuklugu = totalValue;
                fonKarZarar  = kz;
            }
        }

        return new DashboardStatsDTO(sirketFonBuyuklugu, sirketKarZarar, fonBuyuklugu, fonKarZarar);
    }

    public List<Map<String, Object>> getAllAdvisorsWithLivePerformance() {
        List<User> allUsers = userRepository.findAll();

        Map<String, BigDecimal> currentPrices = fundRepository.findAll().stream()
                .collect(Collectors.toMap(Fund::getFundCode, Fund::getCurrentPrice));

        List<Investment> allInvestments = investmentRepository.findAll();

        return allUsers.stream()
                .filter(u -> u.getRole() == Role.ADVISOR || u.getRole() == Role.ADMIN)
                .map(advisor -> {
                    String managedFund  = advisor.getManagedFundCode();
                    BigDecimal currentPrice = currentPrices.getOrDefault(managedFund, BigDecimal.ONE);

                    List<Investment> fundInvs = allInvestments.stream()
                            .filter(inv -> inv.getFundCode().equalsIgnoreCase(managedFund))
                            .toList();

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

    public InvestorPortfolioDTO getInvestorPortfolio(String investorKeycloakId, String fundCode) {
        User investor = userRepository.findByKeycloakId(investorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));

        Fund fund = fundRepository.findByFundCode(fundCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Fon bulunamadı"));

        Investment inv = investmentRepository.findByInvestorId(investor.getId()).stream()
                .filter(i -> i.getFundCode().equalsIgnoreCase(fundCode))
                .findFirst()
                .orElse(new Investment());

        BigDecimal toplamLot    = inv.getLotCount() != null ? inv.getLotCount() : BigDecimal.ZERO;
        BigDecimal yatirilanPara = inv.getBalance() != null ? inv.getBalance()  : BigDecimal.ZERO;
        BigDecimal guncelDeger  = toplamLot.multiply(fund.getCurrentPrice());
        BigDecimal karZararTl   = guncelDeger.subtract(yatirilanPara);

        String karZararYuzde = yatirilanPara.compareTo(BigDecimal.ZERO) > 0
                ? karZararTl.multiply(new BigDecimal("100")).divide(yatirilanPara, 2, RoundingMode.HALF_UP).toString()
                : "0.00";

        List<Investment> allInvs = investmentRepository.findByInvestorId(investor.getId());

        BigDecimal toplamPortfoyBuyuklugu = allInvs.stream()
                .map(i -> {
                    BigDecimal price = fundRepository.findByFundCode(i.getFundCode().toUpperCase())
                            .map(Fund::getCurrentPrice).orElse(BigDecimal.ONE);
                    return i.getLotCount().multiply(price);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal toplamMaliyet = allInvs.stream()
                .map(i -> i.getBalance() != null ? i.getBalance() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal genelKarTl = toplamPortfoyBuyuklugu.subtract(toplamMaliyet);
        String genelKarYuzde = (toplamMaliyet.compareTo(BigDecimal.ZERO) > 0)
                ? genelKarTl.multiply(new BigDecimal("100")).divide(toplamMaliyet, 2, RoundingMode.HALF_UP).toString()
                : "0.00";

        return new InvestorPortfolioDTO(
                toplamLot, karZararTl, karZararYuzde,
                guncelDeger, toplamPortfoyBuyuklugu, genelKarYuzde);
    }

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

    public AdvisorStatsDTO getAdvisorStats(String advisorKeycloakId) {
        User advisor = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Danışman bulunamadı"));

        String managedFundCode = advisor.getManagedFundCode();
        if (managedFundCode == null) return new AdvisorStatsDTO(BigDecimal.ZERO, BigDecimal.ZERO, "0.00");

        Fund fund = fundRepository.findByFundCode(managedFundCode).orElse(null);
        if (fund == null) return new AdvisorStatsDTO(BigDecimal.ZERO, BigDecimal.ZERO, "0.00");

        // Gerçek portföy: nakit + hisselerin güncel piyasa değeri
        List<FundStockHolding> holdings = fundStockHoldingRepository.findByFundCode(managedFundCode.toUpperCase());
        BigDecimal stocksCurrentValue = holdings.stream()
                .map(h -> h.getStock().getCurrentPrice().multiply(h.getLotCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stocksCostValue = holdings.stream()
                .map(h -> h.getAvgCost().multiply(h.getLotCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal commoditiesValue = fundCommodityHoldingRepository
                .findByFundCode(managedFundCode.toUpperCase()).stream()
                .map(h -> h.getTotalCostTry() != null ? h.getTotalCostTry() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // cashBalance 0 ise yatırımcı toplamından hisse ve emtia maliyetini düşerek türet
        BigDecimal cashBalance = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
        if (cashBalance.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal totalInvested = investmentRepository.findByFundCode(managedFundCode)
                    .stream()
                    .map(inv -> inv.getBalance() != null ? inv.getBalance() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cashBalance = totalInvested.subtract(stocksCostValue).subtract(commoditiesValue).max(BigDecimal.ZERO);
        }

        BigDecimal fonBuyuklugu = cashBalance.add(stocksCurrentValue).add(commoditiesValue);
        BigDecimal karZararTl   = stocksCurrentValue.subtract(stocksCostValue);
        String karZararYuzde = stocksCostValue.compareTo(BigDecimal.ZERO) > 0
                ? karZararTl.divide(stocksCostValue, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toString()
                : "0.00";

        return new AdvisorStatsDTO(fonBuyuklugu, karZararTl, karZararYuzde);
    }

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

    public List<Map<String, Object>> getAllInvestorsWithDetailedPortfolios() {
        List<User> investors = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.INVESTOR)
                .toList();

        Map<String, BigDecimal> currentPrices = fundRepository.findAll().stream()
                .collect(Collectors.toMap(Fund::getFundCode, Fund::getCurrentPrice));

        return investors.stream().map(investor -> {
            Map<String, Object> map = new HashMap<>();
            map.put("tcNo",     investor.getTcNo());
            map.put("fullName", investor.getFirstName() + " " + investor.getLastName());
            map.put("email",    investor.getEmail());

            List<Investment> myInvestments = investmentRepository.findByInvestorId(investor.getId());

            List<Map<String, Object>> holdings = myInvestments.stream().map(inv -> {
                BigDecimal price   = currentPrices.getOrDefault(inv.getFundCode().toUpperCase(), BigDecimal.ONE);
                BigDecimal tlValue = inv.getLotCount().multiply(price);

                Map<String, Object> h = new HashMap<>();
                h.put("fundCode", inv.getFundCode());
                h.put("lots",     inv.getLotCount());
                h.put("tlValue",  tlValue);
                return h;
            }).toList();

            map.put("holdings", holdings);

            BigDecimal totalValue = holdings.stream()
                    .map(h -> (BigDecimal) h.get("tlValue"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            map.put("totalPortfolioValue", totalValue);

            return map;
        }).collect(Collectors.toList());
    }

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
