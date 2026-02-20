package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.StockTradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockTradeHistoryRepository extends JpaRepository<StockTradeHistory, Long> {
    List<StockTradeHistory> findByFundCodeOrderByTradeDateDesc(String fundCode);
    List<StockTradeHistory> findAllByOrderByTradeDateDesc();
}
