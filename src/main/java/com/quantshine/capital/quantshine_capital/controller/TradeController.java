package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.service.StockService;
import com.quantshine.capital.quantshine_capital.service.TradeService;
import com.quantshine.capital.quantshine_capital.service.UserService;
import com.quantshine.capital.quantshine_capital.entity.TransactionType;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.entity.Role;
import com.quantshine.capital.quantshine_capital.entity.Investment;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import com.quantshine.capital.quantshine_capital.repository.InvestmentRepository;
import com.quantshine.capital.quantshine_capital.dto.DashboardStatsDTO;
import com.quantshine.capital.quantshine_capital.dto.AdvisorStatsDTO;
import com.quantshine.capital.quantshine_capital.dto.InvestorPortfolioDTO;
import com.quantshine.capital.quantshine_capital.dto.StockTradeRequest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final TradeService tradeService;
    private final UserRepository userRepository;
    private final InvestmentRepository investmentRepository;
    private final StockService stockService;
    private final UserService userService;

    /**
     * Nesne-seviyesi yetkilendirme: ADMIN her fonda işlem yapabilir; ADVISOR yalnızca
     * kendi yönettiği fon (managedFundCode) üzerinde. Aksi halde 403.
     * Rol bazlı @PreAuthorize tek başına yetmez — danışmanlar arası BOLA'yı önler.
     */
    private void assertCanTradeFund(Jwt jwt, String fundCode) {
        User actor = resolveActor(jwt);
        if (actor.getRole() == Role.ADMIN) return;
        String managed = actor.getManagedFundCode();
        if (managed == null || fundCode == null || !managed.equalsIgnoreCase(fundCode)) {
            throw new AccessDeniedException("Bu fon üzerinde işlem yetkiniz yok.");
        }
    }

    /** JWT'den aktif kullanıcıyı DB'den çözer. */
    private User resolveActor(Jwt jwt) {
        return userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new AccessDeniedException("Yetkili kullanıcı bulunamadı."));
    }

    /**
     * Listeyi okuyan kullanıcıya göre kapsar: ADMIN tümünü görür; ADVISOR yalnızca
     * yönettiği fonu; fonu olmayan ADVISOR boş liste alır. Danışmanlar-arası veri
     * sızıntısını (BOLA) önler.
     */
    private List<Map<String, Object>> scopeByFund(Jwt jwt, List<Map<String, Object>> all, String fundKey) {
        User actor = resolveActor(jwt);
        if (actor.getRole() == Role.ADMIN) return all;
        String managed = actor.getManagedFundCode();
        if (managed == null) return List.of();
        return all.stream()
                .filter(m -> managed.equalsIgnoreCase(String.valueOf(m.get(fundKey))))
                .collect(Collectors.toList());
    }

    @GetMapping("/all-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Map<String, Object>>> getAllHistory(@AuthenticationPrincipal Jwt jwt) {
        // ADMIN tümünü, ADVISOR yalnızca kendi fonunu görür.
        return ResponseEntity.ok(scopeByFund(jwt, tradeService.getAllHistoryEnriched(), "fundCode"));
    }

    @GetMapping("/admin-stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DashboardStatsDTO> getAdminStats(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(tradeService.getAdminStats(jwt.getSubject()));
    }

    @GetMapping("/advisor-stats")
    @PreAuthorize("hasRole('ADVISOR')")
    public ResponseEntity<AdvisorStatsDTO> getAdvisorStats(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(tradeService.getAdvisorStats(jwt.getSubject()));
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<String> executeTrade(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal Jwt jwt) {
        // Keycloak'tan gelen kullanıcı local DB'de yoksa (örn. admin /users/me'yi
        // hiç çağırmadıysa) önce senkronla — aksi halde advisor lookup patlar.
        userService.ensureSyncedFromJwt(jwt);
        // Yetkilendirme try bloğundan ÖNCE — AccessDeniedException 403 olarak yükselsin
        String fundCode = (String) payload.get("fundCode");
        assertCanTradeFund(jwt, fundCode);
        try {
            String advisorKeycloakId = jwt.getSubject();
            String investorTc = (String) payload.get("investorTc");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());
            TransactionType type = TransactionType.valueOf(payload.get("type").toString().toUpperCase());

            tradeService.executeTrade(advisorKeycloakId, investorTc, fundCode, amount, type);
            return ResponseEntity.ok("İşlem başarıyla gerçekleşti.");
        } catch (Exception e) {
            // Trade hataları kullanıcıya yararlı (ör. yetersiz bakiye) → mesaj korunur; tam iz logda.
            log.warn("İşlem hatası: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("İşlem hatası: " + e.getMessage());
        }
    }

    @GetMapping("/all-investors-detailed")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Map<String, Object>>> getAllInvestorsDetailed(@AuthenticationPrincipal Jwt jwt) {
        List<Map<String, Object>> all = tradeService.getAllInvestorsWithDetailedPortfolios();
        User actor = resolveActor(jwt);
        if (actor.getRole() == Role.ADMIN) return ResponseEntity.ok(all);

        // ADVISOR: yalnızca yönettiği fondaki holding'leri içeren yatırımcılar.
        String managed = actor.getManagedFundCode();
        if (managed == null) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> scoped = new ArrayList<>();
        for (Map<String, Object> inv : all) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> holdings = (List<Map<String, Object>>) inv.get("holdings");
            if (holdings == null) continue;
            List<Map<String, Object>> kept = holdings.stream()
                    .filter(h -> managed.equalsIgnoreCase(String.valueOf(h.get("fundCode"))))
                    .collect(Collectors.toList());
            if (kept.isEmpty()) continue;
            Map<String, Object> copy = new HashMap<>(inv);
            copy.put("holdings", kept);
            BigDecimal total = kept.stream()
                    .map(h -> (BigDecimal) h.get("tlValue"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            copy.put("totalPortfolioValue", total);
            scoped.add(copy);
        }
        return ResponseEntity.ok(scoped);
    }

    @PostMapping("/stock-execute")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> executeStockTrade(@RequestBody StockTradeRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        // Yetkilendirme try bloğundan ÖNCE — AccessDeniedException 403 olarak yükselsin
        assertCanTradeFund(jwt, request.getFundCode());
        try {
            // Fiyat istemciden alınmaz; StockService piyasa fiyatını kullanır.
            String result = stockService.executeStockTrade(
                    request.getFundCode(),
                    request.getStockCode(),
                    request.getLot(),
                    request.getType()
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // Trade hataları kullanıcıya yararlı (ör. yetersiz bakiye) → mesaj korunur; tam iz logda.
            log.warn("Hisse işlem hatası: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("İşlem Hatası: " + e.getMessage());
        }
    }
    @GetMapping("/stock-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> getStockTradeHistory(@RequestParam(required = false) String fundCode,
                                                  @AuthenticationPrincipal Jwt jwt) {
        User actor = resolveActor(jwt);
        // ADVISOR fonu ne olursa olsun yalnızca kendi yönettiği fonun geçmişini görür.
        if (actor.getRole() != Role.ADMIN) {
            String managed = actor.getManagedFundCode();
            if (managed == null) return ResponseEntity.ok(List.of());
            return ResponseEntity.ok(stockService.getStockTradeHistory(managed));
        }
        if (fundCode != null && !fundCode.isEmpty()) {
            return ResponseEntity.ok(stockService.getStockTradeHistory(fundCode));
        }
        return ResponseEntity.ok(stockService.getAllStockTradeHistory());
    }

    @GetMapping("/my-funds")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<List<String>> getMyFunds(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(tradeService.getInvestorFunds(jwt.getSubject()));
    }

    @GetMapping("/investor-portfolio")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<InvestorPortfolioDTO> getInvestorPortfolio(
            @RequestParam String fundCode,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(tradeService.getInvestorPortfolio(jwt.getSubject(), fundCode));
    }
    @GetMapping("/my-history")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<List<Map<String, Object>>> getMyHistory(@AuthenticationPrincipal Jwt jwt) {
        // Enriched versiyonu kullanıyoruz ki tablo "Anlık Değer" ve "Kâr/Zarar" göstersin
        return ResponseEntity.ok(tradeService.getEnrichedInvestorHistory(jwt.getSubject()));
    }
}