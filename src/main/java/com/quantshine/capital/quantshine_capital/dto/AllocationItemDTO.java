package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Fon varlık dağılımı pasta grafiği için tek bir kalem.
 * value: portföy içindeki yüzde oranı (örn. 35.4 → %35,4)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AllocationItemDTO {
    private String name;
    private BigDecimal value;
}
