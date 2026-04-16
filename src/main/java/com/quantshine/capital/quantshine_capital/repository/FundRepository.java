package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.Fund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface FundRepository extends JpaRepository<Fund, Long> {

    Optional<Fund> findByFundCode(String fundCode);

    boolean existsByFundCode(String fundCode);
}