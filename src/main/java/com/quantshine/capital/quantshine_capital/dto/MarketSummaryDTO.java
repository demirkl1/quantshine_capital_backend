package com.quantshine.capital.quantshine_capital.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketSummaryDTO {
    private String symbol;
    private double price;
    private double changeRate;
    private boolean rising;
}
