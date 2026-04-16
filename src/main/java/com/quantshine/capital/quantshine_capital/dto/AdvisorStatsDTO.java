package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class AdvisorStatsDTO {
    private BigDecimal sorumluFonBuyuklugu; // Danışmanın yönettiği fonun net bakiye toplamı
    private BigDecimal fonKarZararTl;        // (Toplam Lot * Güncel Fiyat) - Bakiye
    private String fonKarZararYuzde;         // Frontend'de kolay göstermek için String formatında %
}