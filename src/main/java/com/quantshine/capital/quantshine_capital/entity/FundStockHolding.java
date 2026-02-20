package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fund_stock_holdings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundStockHolding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String fundCode; // HSF, TEK, MF

    @ManyToOne
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal lotCount;

    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal avgCost;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal totalCost;

    private LocalDateTime purchaseDate;

    @Column(precision = 15, scale = 2)
    private BigDecimal currentValue;

    @PrePersist
    @PreUpdate
    public void calculateValues() {
        if (lotCount != null && avgCost != null) {
            this.totalCost = lotCount.multiply(avgCost);
        }
    }
}