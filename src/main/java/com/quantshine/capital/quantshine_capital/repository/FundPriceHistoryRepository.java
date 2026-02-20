package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.FundPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FundPriceHistoryRepository extends JpaRepository<FundPriceHistory, Long> {

    /** Admin / Danışman: tüm geçmiş, tarih artan sıra */
    List<FundPriceHistory> findByFundCodeOrderByPriceDateAsc(String fundCode);

    /** Belirtilen tarihten itibaren kayıtlar, tarih artan sıra */
    List<FundPriceHistory> findByFundCodeAndPriceDateAfterOrderByPriceDateAsc(
            String fundCode, LocalDateTime startDate);

    /**
     * Belirtilen tarihe en yakın (o tarihe eşit veya önceki) son kaydı getirir.
     * Performans hesaplamada "X gün önceki fiyat" için kullanılır.
     */
    Optional<FundPriceHistory> findFirstByFundCodeAndPriceDateLessThanEqualOrderByPriceDateDesc(
            String fundCode, LocalDateTime date);

    /**
     * İki tarih arasındaki kayıtlar, tarih artan sıra.
     * Grafik noktaları için kullanılır.
     */
    List<FundPriceHistory> findByFundCodeAndPriceDateBetweenOrderByPriceDateAsc(
            String fundCode, LocalDateTime start, LocalDateTime end);
}
