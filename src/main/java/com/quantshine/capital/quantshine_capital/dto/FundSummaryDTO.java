package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Fon listesi sayfasında ({@code GET /api/funds}) döndürülen özet DTO.
 * cashBalance gibi iç bilgiler bu DTO'da yer almaz.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundSummaryDTO {

    /** Fon kodu (örn. IPB, IRF) */
    private String code;

    /** Tam fon ünvanı */
    private String name;

    /** Fon türü */
    private String type;

    /** KIID risk derecesi (1–7) */
    private Integer riskLevel;

    /** Güncel birim fiyat */
    private BigDecimal price;

    /** 1 günlük getiri (%) */
    private BigDecimal day;

    /** 1 aylık getiri (%) */
    private BigDecimal month;

    /** 3 aylık getiri (%) */
    private BigDecimal q3;

    /** 6 aylık getiri (%) */
    private BigDecimal q6;

    /** Yılbaşından bugüne getiri (%) */
    private BigDecimal ytd;

    /** 1 yıllık getiri (%) */
    private BigDecimal year;
}
