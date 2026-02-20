package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // 1. Yatırımcının kendi ekranı için: Tüm işlemlerini en yeni en üstte getirir
    List<Transaction> findByInvestorIdOrderByCreatedAtDesc(Long investorId);

    // 2. Admin/Danışman ekranı için: Sistemdeki TÜM işlemleri en yeni en üstte listeler
    List<Transaction> findAllByOrderByCreatedAtDesc();

    // 3. Filtreleme için: Belirli bir yatırımcının belirli bir fonundaki geçmişi
    List<Transaction> findByInvestorIdAndFundCodeOrderByCreatedAtDesc(Long investorId, String fundCode);

    // 4. Arama Çubuğu için: TC Kimlik No ile arama yapıldığında (Query ile join gerekebilir veya Service'de çözülür)
    // Şimdilik temel listeleme metotları yeterli olacaktır.

}