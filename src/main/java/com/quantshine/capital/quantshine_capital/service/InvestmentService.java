package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.entity.Investment;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.repository.InvestmentRepository;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import com.quantshine.capital.quantshine_capital.repository.FundRepository;
import com.quantshine.capital.quantshine_capital.entity.Fund;
import com.quantshine.capital.quantshine_capital.dto.DashboardStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InvestmentService {

    private final InvestmentRepository investmentRepository;
    private final UserRepository userRepository;
    private final FundRepository fundRepository;

    /**
     * Yatırımcıyı belirli bir fon üzerinden danışmana atar ve başlangıç bakiyesi tanımlar.
     */
    @Transactional
    public void assignInvestment(String investorTc, String advisorTc, String fundCode, boolean isTransfer) {
        User investor = userRepository.findByTcNo(investorTc)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));
        User advisor = userRepository.findByTcNo(advisorTc)
                .orElseThrow(() -> new RuntimeException("Danışman bulunamadı"));

        if (isTransfer) {
            // Transfer: mevcut kaydın danışmanını değiştir
            Investment existingInv = investmentRepository.findByInvestorIdAndFundCode(investor.getId(), fundCode)
                    .orElseThrow(() -> new RuntimeException("Bu fon için mevcut bir yatırım bulunamadı, önce 'Yeni Kayıt' yapmalısınız."));

            existingInv.setAdvisor(advisor);
            investmentRepository.save(existingInv);
        } else {
            // Yeni kayıt: aynı fona ikinci kez kayıt olmayı engelle
            if (investmentRepository.existsByInvestorIdAndFundCode(investor.getId(), fundCode)) {
                throw new RuntimeException("Yatırımcı zaten '" + fundCode + "' fonuna sahip!");
            }

            Investment newInvestment = new Investment();
            newInvestment.setInvestor(investor);
            newInvestment.setAdvisor(advisor);
            newInvestment.setFundCode(fundCode);
            newInvestment.setBalance(BigDecimal.ZERO);
            newInvestment.setLotCount(BigDecimal.ZERO);
            investmentRepository.save(newInvestment);
        }
    }

    /**
     * Danışman: advisorId'ye göre yatırımcılar.
     * Admin: yönettiği fonun (managedFundCode) tüm yatırımcıları.
     *
     * JSON serileştirmede LazyInitializationException'ı önlemek için
     * investor/advisor ilişkilerini JOIN FETCH ile yükleyen repository
     * metodları kullanılıyor.
     */
    @Transactional(readOnly = true)
    public List<Investment> getMyInvestors(User user) {
        if (user.getRole() == com.quantshine.capital.quantshine_capital.entity.Role.ADMIN) {
            String fundCode = user.getManagedFundCode();
            if (fundCode == null || fundCode.isBlank()) return List.of();
            return investmentRepository.findByFundCodeWithRelations(fundCode);
        }
        return investmentRepository.findByAdvisorIdWithRelations(user.getId());
    }

    /**
     * Bir yatırımcının tüm portföyünü görmesi için
     */
    @Transactional(readOnly = true)
    public List<Investment> getMyPortfolio(Long investorId) {
        return investmentRepository.findByInvestorIdWithRelations(investorId);
    }

    @Transactional(readOnly = true)
    public List<User> getMyAdvisors(Long investorId) {
        List<Investment> investments = investmentRepository.findByInvestorIdWithRelations(investorId);
        return investments.stream()
                .map(Investment::getAdvisor)
                .distinct()
                .collect(Collectors.toList());
    }

    public DashboardStatsDTO getAdminStats(String advisorKeycloakId) {
        User admin = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yetkili bulunamadı"));

        List<Investment> allInvestments = investmentRepository.findAll();

        BigDecimal sirketFonBuyuklugu = BigDecimal.ZERO;
        BigDecimal fonBuyuklugu       = BigDecimal.ZERO;

        for (Investment inv : allInvestments) {
            sirketFonBuyuklugu = sirketFonBuyuklugu.add(inv.getBalance());
            if (inv.getFundCode().equalsIgnoreCase(admin.getManagedFundCode())) {
                fonBuyuklugu = fonBuyuklugu.add(inv.getBalance());
            }
        }

        // Kâr/zarar: basit simülasyon (gerçek fiyat entegrasyonuna kadar)
        BigDecimal sirketKarZarar = sirketFonBuyuklugu.multiply(new BigDecimal("0.05"));
        BigDecimal fonKarZarar    = fonBuyuklugu.multiply(new BigDecimal("-0.02"));

        return new DashboardStatsDTO(sirketFonBuyuklugu, sirketKarZarar, fonBuyuklugu, fonKarZarar);
    }

    public List<Map<String, Object>> getAllFundSummaries() {
        List<Investment> allInv  = investmentRepository.findAll();
        List<Fund> allFunds      = fundRepository.findAll();
        List<User> allUsers      = userRepository.findAll();

        Map<String, Long> advisorCountMap = allUsers.stream()
                .filter(u -> u.getManagedFundCode() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        User::getManagedFundCode,
                        java.util.stream.Collectors.counting()
                ));

        List<Map<String, Object>> result = new java.util.ArrayList<>();

        allFunds.forEach(fund -> {
            String code = fund.getFundCode();
            Map<String, Object> map = new java.util.HashMap<>();

            List<Investment> invs = allInv.stream()
                    .filter(i -> i.getFundCode().equalsIgnoreCase(code)).toList();

            map.put("id",           code);
            map.put("fundName",     fund.getFundName());
            map.put("currentPrice", fund.getCurrentPrice());
            map.put("advisorCount", advisorCountMap.getOrDefault(code, 0L));

            long investorCount = invs.stream().map(i -> i.getInvestor().getId()).distinct().count();
            java.math.BigDecimal totalLot = invs.stream()
                    .map(Investment::getLotCount)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

            java.math.BigDecimal totalMaliyet = invs.stream()
                    .map(i -> i.getBalance() != null ? i.getBalance() : java.math.BigDecimal.ZERO)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

            map.put("investorCount", investorCount);
            map.put("totalLot",      totalLot);

            java.math.BigDecimal guncelDeger   = totalLot.multiply(fund.getCurrentPrice());
            java.math.BigDecimal karZararPerc  = java.math.BigDecimal.ZERO;
            if (totalMaliyet.compareTo(java.math.BigDecimal.ZERO) > 0) {
                karZararPerc = guncelDeger.subtract(totalMaliyet)
                        .divide(totalMaliyet, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(new java.math.BigDecimal("100"));
            }
            map.put("profitLossPercentage", karZararPerc.setScale(2, java.math.RoundingMode.HALF_UP));

            result.add(map);
        });
        return result;
    }
}
