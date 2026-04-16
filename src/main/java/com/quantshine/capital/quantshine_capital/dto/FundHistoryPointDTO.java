package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Fon getiri grafiği için tek bir tarih-fiyat noktası.
 * Frontend'de {@code { date, value }} formatında kullanılır.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundHistoryPointDTO {
    /** Eksen etiketi (örn. "15/01", "Oca 25") */
    private String date;
    /** Birim fiyat */
    private BigDecimal value;
}
