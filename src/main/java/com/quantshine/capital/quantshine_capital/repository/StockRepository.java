package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByStockCode(String stockCode);
    boolean existsByStockCode(String stockCode);
}