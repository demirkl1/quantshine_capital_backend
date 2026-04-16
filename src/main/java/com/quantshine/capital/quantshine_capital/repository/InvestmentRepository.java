package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.Investment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface InvestmentRepository extends JpaRepository<Investment, Long> {

    java.util.Optional<Investment> findByInvestorIdAndFundCode(Long investorId, String fundCode);

    List<Investment> findByInvestorId(Long investorId);

    @Modifying
    @Query("DELETE FROM Investment i WHERE i.investor.id = :investorId")
    void deleteByInvestorId(@Param("investorId") Long investorId);
    List<Investment> findByAdvisorId(Long advisorId);
    List<Investment> findByFundCode(String fundCode);
    boolean existsByInvestorIdAndFundCode(Long investorId, String fundCode);

    @Query("SELECT i.advisor FROM Investment i WHERE i.investor.id = :investorId AND i.fundCode = :fundCode")
    Object findAdvisorByInvestorAndFund(@Param("investorId") Long investorId, @Param("fundCode") String fundCode);
}