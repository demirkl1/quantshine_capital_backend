package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.entity.Investment;
import com.quantshine.capital.quantshine_capital.entity.Role;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.repository.FundRepository;
import com.quantshine.capital.quantshine_capital.repository.InvestmentRepository;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import com.quantshine.capital.quantshine_capital.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final InvestmentRepository investmentRepository;
    private final FundRepository fundRepository;

    /**
     * Admin'in yönettiği fonun güncel fiyatını döner.
     */
    @GetMapping("/fund-info")
    public ResponseEntity<Map<String, Object>> getFundInfo(@AuthenticationPrincipal Jwt jwt) {
        User admin = userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new RuntimeException("Admin bulunamadı"));
        Map<String, Object> result = new HashMap<>();
        String fundCode = admin.getManagedFundCode();
        if (fundCode != null) {
            fundRepository.findByFundCode(fundCode)
                    .ifPresent(fund -> result.put("currentPrice", fund.getCurrentPrice()));
        }
        result.putIfAbsent("currentPrice", BigDecimal.ONE);
        return ResponseEntity.ok(result);
    }

    /**
     * Onay bekleyen kullanıcı kayıtlarını listeler.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<User>> getPendingUsers() {
        return ResponseEntity.ok(userService.getPendingApprovals());
    }

    /**
     * Mevcut admin hariç diğer adminleri listeler (transfer hedefleri için).
     */
    @GetMapping("/list-others")
    public ResponseEntity<List<Map<String, Object>>> listOtherAdmins(@AuthenticationPrincipal Jwt jwt) {
        User current = userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new RuntimeException("Admin bulunamadı"));
        List<Map<String, Object>> others = userRepository.findAllByRoleIn(List.of(Role.ADMIN))
                .stream()
                .filter(u -> !u.getId().equals(current.getId()))
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("ad", u.getFirstName());
                    m.put("soyad", u.getLastName());
                    m.put("email", u.getEmail());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(others);
    }

    /**
     * Bu admin'e atanmış yatırımcıları ve portföy özetlerini listeler.
     */
    @GetMapping("/my-investors")
    public ResponseEntity<List<Map<String, Object>>> getMyInvestors(@AuthenticationPrincipal Jwt jwt) {
        User admin = userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new RuntimeException("Admin bulunamadı"));
        List<Investment> investments = investmentRepository.findByAdvisorId(admin.getId());
        List<Map<String, Object>> result = investments.stream().map(inv -> {
            User investor = inv.getInvestor();
            BigDecimal fundPrice = fundRepository.findByFundCode(inv.getFundCode())
                    .map(f -> f.getCurrentPrice())
                    .orElse(BigDecimal.ONE);
            Map<String, Object> m = new HashMap<>();
            m.put("id", investor.getId());
            m.put("ad", investor.getFirstName());
            m.put("soyad", investor.getLastName());
            m.put("email", investor.getEmail());
            m.put("lot", inv.getLotCount());
            m.put("suanDeger", inv.getLotCount().multiply(fundPrice));
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Bekleyen kullanıcıyı onayla (ACCEPTED) veya reddet (REJECTED).
     * Admin kimliği JWT'den alınır, query param'dan değil.
     */
    @PutMapping("/decision/{id}")
    public ResponseEntity<?> makeDecision(@PathVariable Long id, @RequestParam String status) {
        try {
            if ("ACCEPTED".equals(status)) {
                userService.approveUser(id);
                return ResponseEntity.ok("Kullanıcı onaylandı.");
            } else {
                if (!userRepository.existsById(id)) return ResponseEntity.notFound().build();
                userRepository.deleteById(id);
                return ResponseEntity.ok("Kayıt reddedildi.");
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Hata: " + e.getMessage());
        }
    }

    /**
     * Yatırımcının bakiyesini artırır ve lot sayısını günceller.
     */
    @PutMapping("/update-assets-by-email")
    @Transactional
    public ResponseEntity<?> updateAssets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String email,
            @RequestParam BigDecimal depositAmount) {
        User admin = userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new RuntimeException("Admin bulunamadı"));
        User investor = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));
        String fundCode = admin.getManagedFundCode();
        Investment inv = investmentRepository.findByInvestorIdAndFundCode(investor.getId(), fundCode)
                .orElseThrow(() -> new RuntimeException("Bu yatırımcının bu fonda kaydı yok"));
        BigDecimal fundPrice = fundRepository.findByFundCode(fundCode)
                .map(f -> f.getCurrentPrice()).orElse(BigDecimal.ONE);
        BigDecimal addedLot = depositAmount.divide(fundPrice, 4, RoundingMode.HALF_UP);
        inv.setBalance(inv.getBalance().add(depositAmount));
        inv.setLotCount(inv.getLotCount().add(addedLot));
        investmentRepository.save(inv);

        // Fon nakit bakiyesini artır
        fundRepository.findByFundCode(fundCode).ifPresent(fund -> {
            BigDecimal current = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
            fund.setCashBalance(current.add(depositAmount));
            fundRepository.save(fund);
        });

        return ResponseEntity.ok("Bakiye güncellendi.");
    }

    /**
     * Yatırımcının bakiyesinden düşer ve lot sayısını günceller.
     */
    @PutMapping("/withdraw-assets-by-email")
    @Transactional
    public ResponseEntity<?> withdrawAssets(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String email,
            @RequestParam BigDecimal withdrawAmount) {
        User admin = userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new RuntimeException("Admin bulunamadı"));
        User investor = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));
        String fundCode = admin.getManagedFundCode();
        Investment inv = investmentRepository.findByInvestorIdAndFundCode(investor.getId(), fundCode)
                .orElseThrow(() -> new RuntimeException("Bu yatırımcının bu fonda kaydı yok"));
        if (inv.getBalance().compareTo(withdrawAmount) < 0) {
            return ResponseEntity.badRequest().body("Yetersiz bakiye.");
        }
        BigDecimal fundPrice = fundRepository.findByFundCode(fundCode)
                .map(f -> f.getCurrentPrice()).orElse(BigDecimal.ONE);
        BigDecimal removedLot = withdrawAmount.divide(fundPrice, 4, RoundingMode.HALF_UP);
        inv.setBalance(inv.getBalance().subtract(withdrawAmount));
        inv.setLotCount(inv.getLotCount().subtract(removedLot).max(BigDecimal.ZERO));
        investmentRepository.save(inv);

        // Fon nakit bakiyesini düşür
        fundRepository.findByFundCode(fundCode).ifPresent(fund -> {
            BigDecimal current = fund.getCashBalance() != null ? fund.getCashBalance() : BigDecimal.ZERO;
            fund.setCashBalance(current.subtract(withdrawAmount).max(BigDecimal.ZERO));
            fundRepository.save(fund);
        });

        return ResponseEntity.ok("Para çekme işlemi başarılı.");
    }

    /**
     * Yatırımcıyı başka bir admin'e transfer eder.
     * Kaynak admin JWT'den alınır, hedef admin newAdminId param'ından.
     */
    @PutMapping("/transfer/{investorId}")
    @Transactional
    public ResponseEntity<?> transferInvestor(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long investorId,
            @RequestParam Long newAdminId) {
        User admin = userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new RuntimeException("Admin bulunamadı"));
        User newAdmin = userRepository.findById(newAdminId)
                .orElseThrow(() -> new RuntimeException("Hedef admin bulunamadı"));
        List<Investment> toTransfer = investmentRepository.findByAdvisorId(admin.getId())
                .stream()
                .filter(inv -> inv.getInvestor().getId().equals(investorId))
                .collect(Collectors.toList());
        if (toTransfer.isEmpty()) {
            return ResponseEntity.badRequest().body("Transfer edilecek yatırım bulunamadı.");
        }
        toTransfer.forEach(inv -> inv.setAdvisor(newAdmin));
        investmentRepository.saveAll(toTransfer);
        return ResponseEntity.ok("Transfer başarıyla tamamlandı.");
    }
}
