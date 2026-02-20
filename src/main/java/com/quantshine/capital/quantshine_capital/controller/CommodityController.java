package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.entity.Commodity;
import com.quantshine.capital.quantshine_capital.entity.CommodityTradeHistory;
import com.quantshine.capital.quantshine_capital.service.CommodityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/commodities")
@RequiredArgsConstructor
public class CommodityController {

    private final CommodityService commodityService;

    /** Tüm emtia fiyatlarını döner (kimlik doğrulaması yeterli). */
    @GetMapping
    public ResponseEntity<List<Commodity>> getAllCommodities() {
        return ResponseEntity.ok(commodityService.getAllCommodities());
    }

    /** Manuel güncelleme — yalnızca ADMIN/ADVISOR erişebilir. */
    @PostMapping("/update")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<String> manualUpdate() {
        commodityService.updateCommodityPrices();
        return ResponseEntity.ok("Emtia fiyatları güncelleniyor...");
    }

    /** Anlık USD/TRY kurunu döner. */
    @GetMapping("/usdtry")
    public ResponseEntity<BigDecimal> getUsdtryRate() {
        return ResponseEntity.ok(commodityService.getUsdtryRate());
    }

    /**
     * Emtia alım/satım işlemi.
     * Payload: { fundCode, symbol, lot, priceUsd, usdtryRate, type }
     */
    @PostMapping("/trade")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<?> executeTrade(@RequestBody Map<String, Object> payload) {
        try {
            String fundCode  = (String) payload.get("fundCode");
            String symbol    = (String) payload.get("symbol");
            String type      = (String) payload.get("type");
            BigDecimal lot      = new BigDecimal(payload.get("lot").toString());
            BigDecimal priceUsd = new BigDecimal(payload.get("priceUsd").toString());
            BigDecimal rate     = new BigDecimal(payload.get("usdtryRate").toString());

            String result = commodityService.executeCommodityTrade(fundCode, symbol, lot, priceUsd, rate, type);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Belirli bir fonun emtia pozisyonlarını döner. */
    @GetMapping("/holdings")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<Map<String, Object>>> getHoldings(@RequestParam String fundCode) {
        return ResponseEntity.ok(commodityService.getFundCommodityHoldings(fundCode));
    }

    /**
     * Emtia işlem geçmişini döner.
     * fundCode verilirse o fona ait, verilmezse tüm geçmiş döner (ADMIN için).
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVISOR')")
    public ResponseEntity<List<CommodityTradeHistory>> getHistory(
            @RequestParam(required = false) String fundCode) {
        if (fundCode != null && !fundCode.isBlank()) {
            return ResponseEntity.ok(commodityService.getCommodityTradeHistory(fundCode));
        }
        return ResponseEntity.ok(commodityService.getAllCommodityTradeHistory());
    }
}
