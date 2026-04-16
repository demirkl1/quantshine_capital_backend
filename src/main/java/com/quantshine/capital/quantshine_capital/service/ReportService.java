package com.quantshine.capital.quantshine_capital.service;

import com.quantshine.capital.quantshine_capital.dto.ReportResponseDTO;
import com.quantshine.capital.quantshine_capital.entity.Report;
import com.quantshine.capital.quantshine_capital.entity.User;
import com.quantshine.capital.quantshine_capital.repository.ReportRepository;
import com.quantshine.capital.quantshine_capital.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;

    public void sendReport(String advisorKeycloakId, Long investorId, String title, String content) {
        User advisor = userRepository.findByKeycloakId(advisorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Danışman bulunamadı"));

        User investor = userRepository.findById(investorId)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));

        Report report = new Report();
        report.setAdvisor(advisor);
        report.setInvestor(investor);
        report.setTitle(title);
        report.setContent(content);

        reportRepository.save(report);
    }

    @Transactional(readOnly = true)
    public List<ReportResponseDTO> getReportsForInvestor(String investorKeycloakId) {
        User investor = userRepository.findByKeycloakId(investorKeycloakId)
                .orElseThrow(() -> new RuntimeException("Yatırımcı bulunamadı"));

        List<Report> reports = reportRepository.findByInvestorIdOrderByCreatedAtDesc(investor.getId());

        return reports.stream().map(report -> {
            ReportResponseDTO dto = new ReportResponseDTO();
            dto.setId(report.getId());
            dto.setTitle(report.getTitle());
            dto.setContent(report.getContent());
            dto.setCreatedAt(report.getCreatedAt());

            // Lazy alanı transaction içindeyken güvenle okuyoruz
            if (report.getAdvisor() != null) {
                ReportResponseDTO.AdvisorInfo advisorInfo = new ReportResponseDTO.AdvisorInfo();
                advisorInfo.setId(report.getAdvisor().getId());
                advisorInfo.setFirstName(report.getAdvisor().getFirstName());
                advisorInfo.setLastName(report.getAdvisor().getLastName());
                dto.setAdvisor(advisorInfo);
            }

            return dto;
        }).collect(Collectors.toList());
    }
}