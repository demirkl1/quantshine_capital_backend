package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "funds")
@Data
@NoArgsConstructor
public class Fund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Benzersiz fon kodu (büyük harf, maks 10 karakter) */
    @Column(unique = true, nullable = false, length = 10)
    private String fundCode;

    /** Tam fon ünvanı */
    @Column(nullable = false)
    private String fundName;

    /** Birim fiyat (19 hane, 4 ondalık) */
    @Column(name = "current_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal currentPrice;

    /** Son fiyat güncelleme zamanı */
    @Column(name = "last_update", nullable = false)
    private LocalDateTime lastUpdate;

    /** Fondaki nakit bakiyesi (iç kullanım – API'de açıklanmaz) */
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal cashBalance = BigDecimal.ZERO;

    // ── Genel Bilgiler (016 migration ile eklendi) ───────────────

    /** Fon türü: "Değişken Fon", "Fon Sepeti Fonu" vb. */
    @Column(name = "fund_type", length = 100)
    private String fundType = "Değişken Fon";

    /** Para birimi kodu: TRY, USD vb. */
    @Column(length = 10)
    private String currency = "TRY";

    /** TEFAS'ta işlem görüp görmediği */
    private Boolean tefas = false;

    /** KIID uyumlu risk derecesi (1–7) */
    @Column(name = "risk_level")
    private Integer riskLevel = 4;

    /** Fonun kuruluş / başlangıç tarihi */
    @Column(name = "inception_date")
    private LocalDate inceptionDate;
}
