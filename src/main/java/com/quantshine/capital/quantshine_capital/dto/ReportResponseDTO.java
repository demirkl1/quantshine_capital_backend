package com.quantshine.capital.quantshine_capital.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReportResponseDTO {

    private Long id;
    private String title;
    private String content;
    private LocalDateTime createdAt;
    private AdvisorInfo advisor;

    @Data
    public static class AdvisorInfo {
        private Long id;
        private String firstName;
        private String lastName;
    }
}
