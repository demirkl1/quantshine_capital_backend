package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.FundStockHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FundStockHoldingRepository extends JpaRepository<FundStockHolding, Long> {

    // Belirli bir fona (HSF, TEK vb.) ait tüm hisse tutarlarını getirir
    List<FundStockHolding> findByFundCode(String fundCode);

    // Belirli bir fonun içindeki belirli bir hisseyi bulur
    // stock_id üzerinden ilişki kurduğumuz için stock.stockCode üzerinden arama yapar
    Optional<FundStockHolding> findByFundCodeAndStock_StockCode(String fundCode, String stockCode);

    void deleteByFundCode(String fundCode);
}