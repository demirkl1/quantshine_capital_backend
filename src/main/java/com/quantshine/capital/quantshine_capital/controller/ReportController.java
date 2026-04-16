package com.quantshine.capital.quantshine_capital.controller;

import com.quantshine.capital.quantshine_capital.dto.ReportResponseDTO;
import com.quantshine.capital.quantshine_capital.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    // Danışman veya admin rapor gönderir
    @PostMapping("/send")
    @PreAuthorize("hasAnyRole('ADVISOR', 'ADMIN')")
    public ResponseEntity<String> sendReport(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> payload) {
        String advisorKeycloakId = jwt.getSubject();
        Long investorId = Long.valueOf(payload.get("investorId").toString());
        String title = (String) payload.get("title");
        String content = (String) payload.get("content");

        reportService.sendReport(advisorKeycloakId, investorId, title, content);
        return ResponseEntity.ok("Rapor başarıyla gönderildi.");
    }

    // Yatırımcı kendi raporlarını çeker
    @GetMapping("/my-reports")
    @PreAuthorize("hasRole('INVESTOR')")
    public ResponseEntity<List<ReportResponseDTO>> getMyReports(@AuthenticationPrincipal Jwt jwt) {
        String investorKeycloakId = jwt.getSubject();
        return ResponseEntity.ok(reportService.getReportsForInvestor(investorKeycloakId));
    }
}