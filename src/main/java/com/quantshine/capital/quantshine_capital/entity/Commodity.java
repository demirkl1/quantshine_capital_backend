package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "commodities")
@Data
public class Commodity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String symbol; // GC1!, SI1!, UKOIL, vb.

    @Column(length = 50)
    private String nameTr; // Altın, Gümüş, Bakır, vb.

    @Column(precision = 12, scale = 4)
    private BigDecimal currentPrice;

    @Column(precision = 10, scale = 4)
    private BigDecimal change; // Mutlak değişim

    private String changePercent; // Yüzde değişim (+0.52%)

    private LocalDateTime lastUpdate;
}
