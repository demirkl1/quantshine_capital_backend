package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fund_commodity_holdings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundCommodityHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String fundCode;

    @ManyToOne
    @JoinColumn(name = "commodity_id", nullable = false)
    private Commodity commodity;

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal lotCount;

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal avgCostUsd;      // ortalama alış fiyatı (USD)

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal totalCostTry;    // toplam harcama (TRY)

    private LocalDateTime purchaseDate;
}
