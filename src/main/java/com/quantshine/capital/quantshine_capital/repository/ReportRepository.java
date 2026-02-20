package com.quantshine.capital.quantshine_capital.repository;

import com.quantshine.capital.quantshine_capital.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {
    // Yatırımcının kendine gelen raporları görmesi için
    List<Report> findByInvestorIdOrderByCreatedAtDesc(Long investorId);
}