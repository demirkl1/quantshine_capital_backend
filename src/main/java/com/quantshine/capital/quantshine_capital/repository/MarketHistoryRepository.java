package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.MarketHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketHistoryRepository extends JpaRepository<MarketHistory, Long> {

    Optional<MarketHistory> findBySymbolAndDate(String symbol, LocalDate date);

    List<MarketHistory> findBySymbolAndDateGreaterThanEqualOrderByDateAsc(
            String symbol, LocalDate date);
}
