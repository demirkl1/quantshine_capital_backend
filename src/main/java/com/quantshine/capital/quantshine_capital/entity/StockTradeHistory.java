package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_trade_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockTradeHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String fundCode;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(length = 255)
    private String stockName;

    @Column(nullable = false, length = 4)
    private String type; // BUY or SELL

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal lot;

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal price;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private LocalDateTime tradeDate;
}
