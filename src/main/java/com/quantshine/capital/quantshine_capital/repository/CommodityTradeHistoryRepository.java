package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.CommodityTradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommodityTradeHistoryRepository extends JpaRepository<CommodityTradeHistory, Long> {

    List<CommodityTradeHistory> findByFundCodeOrderByTradeDateDesc(String fundCode);

    List<CommodityTradeHistory> findAllByOrderByTradeDateDesc();
}
