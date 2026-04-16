package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Tüm işlemler — investor + advisor tek sorguda JOIN FETCH ile yüklenir.
     * LAZY ilişkilerle LazyInitializationException'ı önler; N+1 yerine 1 sorgu.
     */
    @Query("SELECT t FROM Transaction t " +
           "LEFT JOIN FETCH t.investor " +
           "LEFT JOIN FETCH t.advisor " +
           "ORDER BY t.createdAt DESC")
    List<Transaction> findAllByOrderByCreatedAtDesc();

    /**
     * Belirli bir yatırımcının işlem geçmişi — JOIN FETCH ile tek sorguda.
     */
    @Query("SELECT t FROM Transaction t " +
           "LEFT JOIN FETCH t.investor " +
           "LEFT JOIN FETCH t.advisor " +
           "WHERE t.investor.id = :investorId " +
           "ORDER BY t.createdAt DESC")
    List<Transaction> findByInvestorIdOrderByCreatedAtDesc(@Param("investorId") Long investorId);

    /** Belirli bir yatırımcının belirli bir fonundaki geçmişi */
    List<Transaction> findByInvestorIdAndFundCodeOrderByCreatedAtDesc(Long investorId, String fundCode);
}
