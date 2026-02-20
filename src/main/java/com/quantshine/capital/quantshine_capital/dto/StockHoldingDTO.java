package com.quantshine.capital.quantshine_capital.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class StockHoldingDTO {
    private String stockCode;
    private String stockName;
    private BigDecimal lot;
    private BigDecimal avgCost;
    private BigDecimal currentPrice;
    private BigDecimal costValue;
    private BigDecimal currentValue;
}