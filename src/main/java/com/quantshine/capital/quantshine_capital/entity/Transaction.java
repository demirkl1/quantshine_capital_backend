package com.quantshine.capital.quantshine_capital.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "transactions",
    indexes = {
        @Index(name = "idx_transaction_investor_id", columnList = "investor_id"),
        @Index(name = "idx_transaction_advisor_id",  columnList = "advisor_id"),
        @Index(name = "idx_transaction_fund_code",   columnList = "fund_code"),
        @Index(name = "idx_transaction_created_at",  columnList = "created_at")
    }
)
@Data
public class Transaction {
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "lot_count", nullable = false, precision = 19, scale = 4)
    private BigDecimal lotCount;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
