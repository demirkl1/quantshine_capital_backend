package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Piyasa sembollerinin (BIST100, USDTRY, GOLD, vb.) günlük kapanış snapshot'ları.
 * TradingView Scanner'dan her gün bir kez çekilip burada saklanır; zamanla
 * gerçek bir tarihsel seri birikir ve AdminAnasayfa benchmark grafiği bundan beslenir.
 */
@Entity
@Table(
    name = "market_history",
    uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "date"}),
    indexes = {
        @Index(name = "idx_market_history_symbol_date", columnList = "symbol,date")
    }
)
@Data
public class MarketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;   // USD, EUR, BIST, GOLD, SILVER, THYAO...

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal close;
}
