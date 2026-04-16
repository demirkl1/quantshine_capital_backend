package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity
@Table(
    name = "investments",
    indexes = {
        @Index(name = "idx_investment_investor_id", columnList = "investor_id"),
        @Index(name = "idx_investment_advisor_id",  columnList = "advisor_id"),
        @Index(name = "idx_investment_fund_code",   columnList = "fund_code")
    }
)
@Data
public class Investment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: ilişki sadece açıkça erişildiğinde yüklenir — N+1 önlenir
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "investor_id", nullable = false)
    private User investor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advisor_id", nullable = false)
    private User advisor;

    @Column(name = "fund_code", nullable = false)
    private String fundCode;

    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "lot_count", nullable = false, precision = 19, scale = 4)
    private BigDecimal lotCount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
