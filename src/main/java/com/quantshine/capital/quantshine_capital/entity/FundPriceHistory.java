package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "fund_price_history",
    indexes = {
        // Bileşik index: fon kodu + tarih (DESC) — getiri hesaplamalarında kritik
        @Index(name = "idx_fph_fund_code_date", columnList = "fund_code, price_date DESC"),
        @Index(name = "idx_fph_fund_code",      columnList = "fund_code")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FundPriceHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_code", nullable = false)
    private String fundCode;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "price_date", nullable = false)
    private LocalDateTime priceDate;
}
