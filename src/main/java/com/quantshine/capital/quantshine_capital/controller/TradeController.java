package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.service.StockService;
import com.quantshine.capital.quantshine_capital.service.TradeService;
import com.quantshine.capital.quantshine_capital.service.UserService;
import com.quantshine.capital.quantshine_capital.entity.TransactionType;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.entity.Investment;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import com.quantshine.capital.quantshine_capital.repository.InvestmentRepository;
import com.quantshine.capital.quantshine_capital.dto.DashboardStatsDTO;
import com.quantshine.capital.quantshine_capital.dto.AdvisorStatsDTO;
import com.quantshine.capital.quantshine_capital.dto.InvestorPortfolioDTO;
import com.quantshine.capital.quantshine_capital.dto.StockTradeRequest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final UserRepository userRepository;
    private final InvestmentRepository investmentRepository;
    private final StockService stockService;
    private final UserService userService;

    @GetMapping("/all-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Map<String, Object>>> getAllHistory() {
        // TradeService içindeki zenginleştirilmiş geçmiş verisini döner
        return ResponseEntity.ok(tradeService.getAllHistoryEnriched());
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
        try {
            // Keycloak'tan gelen kullanıcı local DB'de yoksa (örn. admin /users/me'yi
            // hiç çağırmadıysa) önce senkronla — aksi halde advisor lookup patlar.
            userService.ensureSyncedFromJwt(jwt);
            String advisorKeycloakId = jwt.getSubject();
            String investorTc = (String) payload.get("investorTc");
            String fundCode = (String) payload.get("fundCode");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());
            TransactionType type = TransactionType.valueOf(payload.get("type").toString().toUpperCase());

            tradeService.executeTrade(advisorKeycloakId, investorTc, fundCode, amount, type);
            return ResponseEntity.ok("İşlem başarıyla gerçekleşti.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("İşlem hatası: " + e.getMessage());
        }
    }

    @GetMapping("/all-investors-detailed")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Map<String, Object>>> getAllInvestorsDetailed() {
        return ResponseEntity.ok(tradeService.getAllInvestorsWithDetailedPortfolios());
    }

    @PostMapping("/stock-execute")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> executeStockTrade(@RequestBody StockTradeRequest request) {
        try {
            String result = stockService.executeStockTrade(
                    request.getFundCode(),
                    request.getStockCode(),
                    request.getLot(),
                    request.getPrice(),
                    request.getType()
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("İşlem Hatası: " + e.getMessage());
        }
    }
    @GetMapping("/stock-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> getStockTradeHistory(@RequestParam(required = false) String fundCode) {
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