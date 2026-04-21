package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fon detay sayfasında ({@code GET /api/funds/{code}}) döndürülen DTO.
 * Performans verileri ve varlık dağılımını içerir.
 * cashBalance ve iç portföy verisi açıklanmaz.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundDetailDTO {

    private String code;
    private String name;
    private String type;
    private String currency;
    private Boolean tefas;

    /** Güncel birim fiyat */
    private BigDecimal price;

    /** Toplam portföy değeri (₺) */
    private BigDecimal totalValue;

    /** Tüm yatırımcı lotlarının toplamı */
    private BigDecimal totalLot;

    /** Kuruluş tarihi (yyyy-MM-dd) */
    private String inceptionDate;

    /** KIID risk derecesi (1–7) */
    private Integer riskLevel;

    /** Dönemlik getiri yüzdelikleri */
    private PerformanceDTO performance;

    /** Varlık dağılımı (pasta grafik için) */
    private List<AllocationItemDTO> allocation;

    // ── İç sınıflar ─────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceDTO {
        private BigDecimal day;
        private BigDecimal month;
        private BigDecimal q3;
        private BigDecimal q6;
        private BigDecimal ytd;
        private BigDecimal year;
    }
}
