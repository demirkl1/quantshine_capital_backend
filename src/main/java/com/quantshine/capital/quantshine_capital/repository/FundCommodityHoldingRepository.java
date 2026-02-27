package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.FundCommodityHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FundCommodityHoldingRepository extends JpaRepository<FundCommodityHolding, Long> {

    List<FundCommodityHolding> findByFundCode(String fundCode);

    Optional<FundCommodityHolding> findByFundCodeAndCommodity_Symbol(String fundCode, String symbol);

    void deleteByFundCode(String fundCode);
}
