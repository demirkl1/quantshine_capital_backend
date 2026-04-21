package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.dto.FundDetailDTO;
import com.quantshine.capital.quantshine_capital.dto.FundHistoryPointDTO;
import com.quantshine.capital.quantshine_capital.dto.FundPortfolioDTO;
import com.quantshine.capital.quantshine_capital.dto.FundSummaryDTO;
import com.quantshine.capital.quantshine_capital.entity.Fund;
import com.quantshine.capital.quantshine_capital.entity.FundPriceHistory;
import com.quantshine.capital.quantshine_capital.repository.FundPriceHistoryRepository;
import com.quantshine.capital.quantshine_capital.repository.FundRepository;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import com.quantshine.capital.quantshine_capital.service.FundService;
import com.quantshine.capital.quantshine_capital.service.InvestmentService;
import com.quantshine.capital.quantshine_capital.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/funds")
@RequiredArgsConstructor
public class FundController {

    private final FundRepository             fundRepository;
    private final UserRepository             userRepository;
    private final InvestmentService          investmentService;
    private final FundService                fundService;
    private final FundPriceHistoryRepository historyRepository;
    private final StockService               stockService;

    /** Fon kodu doğrulama: yalnızca büyük harf + rakam, 2–10 karakter */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9]{2,10}$");

    private boolean isValidCode(String code) {
        return code != null && CODE_PATTERN.matcher(code.toUpperCase()).matches();
    }

    // ════════════════════════════════════════════════════════════
    //  Genel (Public) Endpoint'ler
    // ════════════════════════════════════════════════════════════

    /**
     * Tüm fonları performans verileriyle döndürür.
     * Fonlar sayfası için kullanılır. cashBalance açıklanmaz.
     *
     * GET /api/funds
     */
    @GetMapping
    public ResponseEntity<List<FundSummaryDTO>> getAllFunds() {
        return ResponseEntity.ok(fundService.getAllFundSummaries());
    }

    /**
     * Tek bir fonun detayını (performans + varlık dağılımı) döndürür.
     * cashBalance ve iç portföy bilgisi açıklanmaz.
     *
     * GET /api/funds/{code}
     */
    @GetMapping("/{code}")
    public ResponseEntity<FundDetailDTO> getFundDetail(@PathVariable String code) {
        if (!isValidCode(code)) {
            return ResponseEntity.badRequest().build();
        }
        return fundService.getFundDetail(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Fon birim fiyat geçmişini grafik için döndürür.
     * period: 1A (1 ay), 3A, 6A, 1Y, 3Y, 5Y
     *
     * GET /api/funds/{code}/history?period=1A
     */
    @GetMapping("/{code}/history")
    public ResponseEntity<List<FundHistoryPointDTO>> getFundHistory(
            @PathVariable String code,
            @RequestParam(defaultValue = "1A") String period) {

        if (!isValidCode(code)) {
            return ResponseEntity.badRequest().build();
        }

        // period → başlangıç tarihi
        LocalDateTime startDate = switch (period) {
            case "1H"  -> LocalDateTime.now().minusDays(7);
            case "3A"  -> LocalDateTime.now().minusDays(90);
            case "6A"  -> LocalDateTime.now().minusDays(180);
            case "1Y"  -> LocalDateTime.now().minusDays(365);
            case "3Y"  -> LocalDateTime.now().minusDays(365 * 3L);
            case "5Y"  -> LocalDateTime.now().minusDays(365 * 5L);
            default    -> LocalDateTime.now().minusDays(30);  // "1A"
        };

        // Tarih ekseni formatı: uzun dönemlerde "Oca 25", kısada "15/01"
        boolean longPeriod = period.equals("1Y") || period.equals("3Y") || period.equals("5Y")
                || period.equals("6A");
        Locale trLocale = Locale.forLanguageTag("tr-TR");
        DateTimeFormatter fmt = longPeriod
                ? DateTimeFormatter.ofPattern("MMM yy", trLocale)
                : DateTimeFormatter.ofPattern("dd/MM", trLocale);

        List<FundHistoryPointDTO> result = historyRepository
                .findByFundCodeAndPriceDateAfterOrderByPriceDateAsc(
                        code.trim().toUpperCase(), startDate)
                .stream()
                .map(h -> new FundHistoryPointDTO(
                        h.getPriceDate().format(fmt),
                        h.getPrice()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════
    //  Yönetici (Admin) Endpoint'leri
    // ════════════════════════════════════════════════════════════

    /**
     * Yeni fon oluşturur.
     * POST /api/funds
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createFund(@RequestBody Fund fund) {
        if (fund.getFundCode() == null || fund.getFundCode().isBlank()) {
            return ResponseEntity.badRequest().body("Hata: Fon kodu boş olamaz!");
        }
        if (!isValidCode(fund.getFundCode())) {
            return ResponseEntity.badRequest().body("Hata: Fon kodu yalnızca büyük harf ve rakam içerebilir (2–10 karakter).");
        }
        if (fund.getFundName() == null || fund.getFundName().isBlank()) {
            return ResponseEntity.badRequest().body("Hata: Fon adı boş olamaz!");
        }
        if (fund.getCurrentPrice() == null) {
            return ResponseEntity.badRequest().body("Hata: Başlangıç fiyatı girilmedi!");
        }
        if (fundRepository.existsByFundCode(fund.getFundCode().toUpperCase())) {
            return ResponseEntity.badRequest().body("Hata: Bu fon kodu zaten kullanımda!");
        }
        fund.setFundCode(fund.getFundCode().toUpperCase());
        fund.setLastUpdate(LocalDateTime.now());
        if (fund.getCashBalance() == null) {
            fund.setCashBalance(java.math.BigDecimal.ZERO);
        }
        return ResponseEntity.ok(fundRepository.save(fund));
    }

    /**
     * Danışmanı fona atar.
     * PUT /api/funds/assign-advisor
     */
    @PutMapping("/assign-advisor")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> assignAdvisorToFund(
            @RequestParam String advisorTc,
            @RequestParam String fundCode) {
        return userRepository.findByTcNo(advisorTc)
                .map(user -> {
                    if (!fundRepository.existsByFundCode(fundCode.toUpperCase())) {
                        return ResponseEntity.badRequest()
                                .body("Hata: " + fundCode + " kodlu fon bulunamadı!");
                    }
                    user.setManagedFundCode(fundCode.toUpperCase());
                    userRepository.save(user);
                    return ResponseEntity.ok(user.getFirstName() + " " + user.getLastName()
                            + " başarıyla " + fundCode.toUpperCase() + " fonuna danışman olarak atandı.");
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Tüm fonların yatırımcı bazlı özetini döndürür.
     * GET /api/funds/all-details
     */
    @GetMapping("/all-details")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getAllFundDetails() {
        return ResponseEntity.ok(investmentService.getAllFundSummaries());
    }

    /**
     * Belirli bir fonun fiyat geçmişini tarih sırasıyla döndürür.
     * filter: 1H, 1A (varsayılan), 3A, 6A, 1Y, ALL
     * GET /api/funds/history/{fundCode}?filter=1A
     */
    @GetMapping("/history/{fundCode}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR', 'INVESTOR')")
    public ResponseEntity<List<Map<String, Object>>> getFullFundHistory(
            @PathVariable String fundCode,
            @RequestParam(defaultValue = "ALL") String filter) {

        if (!isValidCode(fundCode)) {
            return ResponseEntity.badRequest().build();
        }

        LocalDateTime startDate = switch (filter.toUpperCase()) {
            case "1H"  -> LocalDateTime.now().minusDays(7);
            case "1A"  -> LocalDateTime.now().minusDays(30);
            case "3A"  -> LocalDateTime.now().minusDays(90);
            case "6A"  -> LocalDateTime.now().minusDays(180);
            case "1Y"  -> LocalDateTime.now().minusDays(365);
            default    -> LocalDateTime.of(2000, 1, 1, 0, 0); // ALL
        };

        List<FundPriceHistory> historyList = filter.equalsIgnoreCase("ALL")
                ? historyRepository.findByFundCodeOrderByPriceDateAsc(fundCode.toUpperCase())
                : historyRepository.findByFundCodeAndPriceDateAfterOrderByPriceDateAsc(
                        fundCode.toUpperCase(), startDate);

        List<Map<String, Object>> response = new ArrayList<>(historyList.stream()
                .map(h -> {
                    Map<String, Object> point = new HashMap<>();
                    point.put("date",  h.getPriceDate().toLocalDate().toString());
                    point.put("price", h.getPrice());
                    return point;
                })
                .collect(Collectors.toList()));

        // Kayıt yoksa mevcut fon fiyatını tek nokta olarak döndür (grafik çizilsin)
        if (response.isEmpty()) {
            fundRepository.findByFundCode(fundCode.toUpperCase()).ifPresent(fund -> {
                if (fund.getCurrentPrice() != null) {
                    Map<String, Object> point = new HashMap<>();
                    point.put("date",  LocalDateTime.now().toLocalDate().toString());
                    point.put("price", fund.getCurrentPrice());
                    response.add(point);
                }
            });
        }

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════
    //  Admin / Danışman Endpoint'leri
    // ════════════════════════════════════════════════════════════

    /**
     * Fonun portföy detayını (hisse ve emtia pozisyonları) döndürür.
     * GET /api/funds/{fundCode}/portfolio
     */
    @GetMapping("/{fundCode}/portfolio")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<FundPortfolioDTO> getFundPortfolio(
            @PathVariable String fundCode) {

        if (!isValidCode(fundCode)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(stockService.getFundPortfolio(fundCode));
    }

    /**
     * Fonu siler.
     * Ön koşul: fonda atanmış danışman ve aktif yatırımcı (lot > 0) olmamalıdır.
     * İşlem/fiyat geçmişi silinmez.
     * DELETE /api/funds/{fundCode}
     */
    @DeleteMapping("/{fundCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteFund(@PathVariable String fundCode) {
        if (!isValidCode(fundCode)) {
            return ResponseEntity.badRequest().body("Geçersiz fon kodu.");
        }
        try {
            fundService.deleteFund(fundCode.toUpperCase());
            return ResponseEntity.ok("Fon başarıyla silindi.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Portföy değerine göre fon birim fiyatını günceller.
     * POST /api/funds/{fundCode}/update-price
     */
    @PostMapping("/{fundCode}/update-price")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> updateFundPrice(@PathVariable String fundCode) {
        if (!isValidCode(fundCode)) {
            return ResponseEntity.badRequest().body("Hata: Geçersiz fon kodu.");
        }
        try {
            fundService.updateFundPriceBasedOnPortfolio(fundCode);
            return ResponseEntity.ok("Fon fiyatı güncellendi.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Hata: " + e.getMessage());
        }
    }
}
