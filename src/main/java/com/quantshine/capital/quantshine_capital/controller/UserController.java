package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.entity.Investment;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.entity.Role;
import com.quantshine.capital.quantshine_capital.service.UserService;
import com.quantshine.capital.quantshine_capital.service.InvestmentService;
import com.quantshine.capital.quantshine_capital.service.TradeService;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.quantshine.capital.quantshine_capital.dto.AdvisorProfileDTO;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final TradeService tradeService;
    private final InvestmentService investmentService;
    private final UserRepository userRepository;

    // --- PROFIL VE ONAY ISLEMLERI ---

    @GetMapping("/me")
    public User getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        User user = new User();
        user.setKeycloakId(jwt.getSubject());
        user.setEmail(jwt.getClaimAsString("email"));
        user.setFirstName(jwt.getClaimAsString("given_name"));
        user.setLastName(jwt.getClaimAsString("family_name"));
        return userService.syncUserWithIdp(user);
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getPendingUsers() {
        return ResponseEntity.ok(userService.getPendingApprovals());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> approveUser(@PathVariable Long id) {
        try {
            userService.approveUser(id);
            return ResponseEntity.ok("Kullanıcı onaylandı ve Keycloak hesabı oluşturuldu.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Hata: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> rejectUser(@PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        userRepository.deleteById(id);
        return ResponseEntity.ok("Kayıt reddedildi ve silindi.");
    }

    // --- LISTELEME ISLEMLERI ---

    @GetMapping("/investors")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getInvestors() {
        return ResponseEntity.ok(userService.getApprovedInvestors());
    }

    @GetMapping("/advisors")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Map<String, Object>>> getAllAdvisors() {
        return ResponseEntity.ok(tradeService.getAllAdvisorsWithStats());
    }

    @PutMapping("/assign-investment")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> assignInvestment(
            @RequestParam String investorTc,
            @RequestParam String advisorTc,
            @RequestParam String fundCode,
            @RequestParam(defaultValue = "false") boolean isTransfer) {
        try {
            investmentService.assignInvestment(investorTc, advisorTc, fundCode.toUpperCase(), isTransfer);
            return ResponseEntity.ok(isTransfer ? "Transfer başarıyla tamamlandı." : "Yeni yatırım kaydı oluşturuldu.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Atama hatası: " + e.getMessage());
        }
    }

    @GetMapping("/{investorId}/portfolio")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Investment>> getInvestorPortfolio(@PathVariable Long investorId) {
        return ResponseEntity.ok(investmentService.getMyPortfolio(investorId));
    }

    @GetMapping("/my-investors")
    @PreAuthorize("hasAnyRole('ADVISOR', 'ADMIN')")
    public ResponseEntity<List<Investment>> getMyInvestors(@AuthenticationPrincipal Jwt jwt) {
        return userRepository.findByKeycloakId(jwt.getSubject())
                .map(user -> ResponseEntity.ok(investmentService.getMyInvestors(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{userId}/transfer")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> transferAdvisor(@PathVariable Long userId, @RequestParam String fundCode) {
        return userRepository.findById(userId)
                .map(user -> {
                    if (user.getRole() != Role.ADVISOR) {
                        return ResponseEntity.badRequest().body("Sadece danışmanlar transfer edilebilir.");
                    }
                    user.setManagedFundCode(fundCode.toUpperCase());
                    userRepository.save(user);
                    return ResponseEntity.ok("Danışman " + fundCode + " fonuna transfer edildi.");
                })
                .orElse(ResponseEntity.notFound().build());
    }
    @GetMapping("/my-advisors-profiles")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<List<AdvisorProfileDTO>> getMyAdvisorsProfiles(@AuthenticationPrincipal Jwt jwt) {
        return userRepository.findByKeycloakId(jwt.getSubject())
                .map(investor -> {
                    List<AdvisorProfileDTO> profiles = investmentService.getMyAdvisors(investor.getId())
                            .stream()
                            .map(adv -> new AdvisorProfileDTO(
                                    adv.getId(),
                                    adv.getFirstName(),
                                    adv.getLastName(),
                                    adv.getEmail(),
                                    adv.getManagedFundCode(),
                                    adv.getDescription()
                            ))
                            .collect(Collectors.toList());
                    return ResponseEntity.ok(profiles);
                })
                .orElse(ResponseEntity.notFound().build());
    }
    @PutMapping("/update-description")
    @PreAuthorize("hasAnyRole('ADVISOR', 'ADMIN')")
    public ResponseEntity<?> updateDescription(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, String> payload) {

        return userRepository.findByKeycloakId(jwt.getSubject())
                .map(user -> {
                    String newDescription = payload.get("description");
                    user.setDescription(newDescription);

                    userRepository.save(user);
                    return ResponseEntity.ok("Profil açıklaması güncellendi.");
                })
                .orElse(ResponseEntity.notFound().build());
    }
}