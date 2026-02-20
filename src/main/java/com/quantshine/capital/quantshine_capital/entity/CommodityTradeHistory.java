package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "commodity_trade_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommodityTradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String fundCode;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(length = 50)
    private String nameTr;

    @Column(nullable = false, length = 4)
    private String type;            // BUY or SELL

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal lot;

    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal priceUsd;    // işlem anındaki USD fiyatı

    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal usdtryRate;  // işlem anındaki USD/TRY kuru

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal totalAmountTry;

    @Column(nullable = false)
    private LocalDateTime tradeDate;
}
