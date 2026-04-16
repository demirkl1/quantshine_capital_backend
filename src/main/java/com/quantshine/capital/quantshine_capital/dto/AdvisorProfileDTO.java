package com.quantshine.capital.quantshine_capital.dto;
// Kendi paket yoluna göre ayarla

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
public class AdvisorProfileDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String managedFundCode;
    private String description; // Profildeki "Hakkında" yazısı için
}
