package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.FundStockHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FundStockHoldingRepository extends JpaRepository<FundStockHolding, Long> {

    /** Belirli bir fonun hisse pozisyonları */
    List<FundStockHolding> findByFundCode(String fundCode);

    /** Belirli bir fonun belirli bir hissesi */
    Optional<FundStockHolding> findByFundCodeAndStock_StockCode(String fundCode, String stockCode);

    /**
     * Tüm fonların hisse pozisyonlarını ve stock bilgisini tek JOIN sorgusunda getirir.
     * getAdminStats() gibi döngüsel sorgular yerine kullanılır — N sorguyu 1'e indirir.
     */
    @Query("SELECT h FROM FundStockHolding h JOIN FETCH h.stock")
    List<FundStockHolding> findAllWithStock();

    void deleteByFundCode(String fundCode);
}
