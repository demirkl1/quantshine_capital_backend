package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stocks")
@Data
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 10)
    private String stockCode; // THYAO, ASELS, vb.

    @Column(length = 100)
    private String stockName; // Türk Hava Yolları

    @Column(precision = 10, scale = 4)
    private BigDecimal currentPrice; // Anlık fiyat

    @Column(precision = 10, scale = 4)
    private BigDecimal previousClose; // Önceki kapanış

    @Column(name = "price_change", precision = 10, scale = 4)
    private BigDecimal change; // Fiyat değişimi

    private String changePercent; // Yüzde değişim

    private LocalDateTime lastUpdate; // Son güncelleme zamanı
}